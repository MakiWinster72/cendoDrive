package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.drive.FileFeatureDtos.*;
import com.cendodrive.user.User;
import java.io.*;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileFeatureService {
  private final DriveFileRepository files;
  private final DriveService drive;
  private final FileQuotaService quota;

  public FileFeatureService(DriveFileRepository files, DriveService drive, FileQuotaService quota) {
    this.files = files;
    this.drive = drive;
    this.quota = quota;
  }

  @Transactional(readOnly = true)
  public List<FileResponse> favorites(User user) {
    return files.findAllByOwnerId(user.getId()).stream().filter(DriveFile::isFavorite)
        .filter(f -> drive.activeTree(user, f) && !drive.hiddenTree(user, f)).map(FileResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public List<FileResponse> hidden(User user) {
    return files.findAllByOwnerId(user.getId()).stream().filter(DriveFile::isHidden)
        .filter(f -> drive.activeTree(user, f))
        .filter(f -> f.getParentId() == null || !drive.hiddenTree(user, drive.requireOwned(user, f.getParentId())))
        .map(FileResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public List<FileResponse> folders(User user) {
    return files.findAllByOwnerId(user.getId()).stream().filter(DriveFile::isFolder)
        .filter(f -> drive.activeTree(user, f))
        .sorted(Comparator.comparing(DriveFile::getName, String.CASE_INSENSITIVE_ORDER))
        .map(FileResponse::from).toList();
  }

  @Transactional
  public List<FileResponse> flag(User user, FlagRequest request, boolean hidden) {
    quota.lock(user);
    List<DriveFile> selected = selection(user, request.ids());
    selected.forEach(f -> {
      if (hidden)
        f.setHidden(request.value());
      else
        f.setFavorite(request.value());
    });
    return files.saveAllAndFlush(selected).stream().map(FileResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public Details details(User user, Long id) {
    DriveFile f = drive.requireActiveOwned(user, id);
    List<DriveFile> contents = f.isFolder() ? drive.subtree(user, List.of(f), false) : List.of(f);
    long bytes = contents.stream().filter(x -> !x.isFolder()).mapToLong(DriveFile::getSize).sum();
    long count = contents.stream().filter(x -> !x.isFolder()).count();
    long folders = contents.stream().filter(DriveFile::isFolder).count() - (f.isFolder() ? 1 : 0);
    var names = new ArrayDeque<String>();
    DriveFile cursor = f;
    while (cursor != null) {
      names.addFirst(cursor.getName());
      cursor = cursor.getParentId() == null ? null : drive.requireOwned(user, cursor.getParentId());
    }
    return new Details(FileResponse.from(f), f.getCreatedAt().atOffset(ZoneOffset.UTC).toInstant().toString(),
        "/" + String.join("/", names), bytes, count, folders);
  }

  @Transactional
  public List<FileResponse> move(User user, TargetRequest request) {
    quota.lock(user);
    List<DriveFile> roots = roots(user, selection(user, request.ids()));
    List<FileResponse> result = new ArrayList<>();
    for (DriveFile root : roots)
      result.add(drive.move(user, root.getId(), new MoveRequest(request.parentId())));
    return result;
  }

  @Transactional(rollbackFor = IOException.class)
  public List<FileResponse> copy(User user, TargetRequest request) throws IOException {
    quota.lock(user);
    if (request.parentId() != null)
      drive.requireFolder(user, request.parentId());
    List<DriveFile> roots = roots(user, selection(user, request.ids()));
    for (DriveFile root : roots)
      if (root.isFolder() && request.parentId() != null)
        drive.ensureNotDescendant(user, root.getId(), request.parentId());
    List<DriveFile> contents = drive.subtree(user, roots, false);
    if (contents.size() > 10000)
      throw error("TOO_MANY_FILES", "Copy at most 10000 items at a time");
    quota.check(user, contents.stream().filter(f -> !f.isFolder()).mapToLong(DriveFile::getSize).sum(), null);
    Set<Long> rootIds = new HashSet<>();
    roots.forEach(f -> rootIds.add(f.getId()));
    Map<Long, Long> newIds = new HashMap<>();
    List<FileResponse> result = new ArrayList<>();
    for (DriveFile source : contents) {
      Long parent = rootIds.contains(source.getId()) ? request.parentId() : newIds.get(source.getParentId());
      if (!rootIds.contains(source.getId()) && parent == null)
        throw error("INVALID_TREE", "Missing copied parent");
      String name = copyName(user, parent, source.getName());
      DriveFile copied;
      if (source.isFolder()) {
        copied = DriveFile.folder(user.getId(), parent, name);
        copied.setHidden(source.isHidden());
        copied = files.saveAndFlush(copied);
      } else {
        Path temp = Files.createTempFile("cendo-copy-", ".tmp");
        try {
          DriveService.Download download = drive.download(user, source.getId());
          try (OutputStream output = Files.newOutputStream(temp)) {
            download.body().writeTo(output);
          }
          if (Files.size(temp) != source.getSize())
            throw new IOException("Copied content size mismatch");
          FileResponse saved;
          try (InputStream input = Files.newInputStream(temp)) {
            saved = drive.uploadStream(user, input, source.getSize(), name, parent, null);
          }
          copied = drive.requireActiveOwned(user, Long.valueOf(saved.id()));
          copied.setHidden(source.isHidden());
          copied = files.saveAndFlush(copied);
        } finally {
          Files.deleteIfExists(temp);
        }
      }
      newIds.put(source.getId(), copied.getId());
      result.add(FileResponse.from(copied));
    }
    return result;
  }

  @Transactional
  public List<FileResponse> organize(User user, OrganizeRequest request) {
    quota.lock(user);
    List<DriveFile> selected = selection(user, request.ids());
    if (selected.stream().anyMatch(DriveFile::isFolder))
      throw error("ONLY_FILES", "Organize accepts files only");
    List<DriveFile> all = new ArrayList<>(files.findAllByOwnerId(user.getId()));
    Map<Group, DriveFile> targets = new HashMap<>();
    List<DriveFile> changed = new ArrayList<>();
    for (DriveFile source : selected) {
      String category = category(source.getName());
      if (source.getParentId() != null && drive.requireFolder(user, source.getParentId()).getName().equals(category)) {
        changed.add(source);
        continue;
      }
      Group group = new Group(source.getParentId(), category);
      DriveFile target = targets.get(group);
      if (target == null) {
        target = all.stream().filter(f -> !f.isDeleted() && Objects.equals(f.getParentId(), group.parentId())
            && f.getName().equalsIgnoreCase(group.name())).findFirst().orElse(null);
        if (target != null && (!target.isFolder() || target.isHidden()))
          throw new DriveFailure(HttpStatus.CONFLICT, "NAME_CONFLICT",
              "Category name is occupied by a file or hidden folder");
        if (target == null) {
          target = files.saveAndFlush(DriveFile.folder(user.getId(), group.parentId(), group.name()));
          all.add(target);
          changed.add(target);
        }
        targets.put(group, target);
      }
      drive.requireAvailableName(user.getId(), target.getId(), source.getName(), source);
      source.moveTo(target.getId());
      changed.add(source);
    }
    return files.saveAllAndFlush(changed).stream().map(FileResponse::from).toList();
  }

  private List<DriveFile> selection(User user, List<Long> ids) {
    List<DriveFile> selected = drive.requireSelection(user, new FileIdsRequest(ids));
    selected.forEach(f -> drive.requireActiveOwned(user, f.getId()));
    return selected;
  }

  private List<DriveFile> roots(User user, List<DriveFile> selected) {
    Set<Long> ids = new HashSet<>();
    selected.forEach(f -> ids.add(f.getId()));
    return selected.stream().filter(f -> {
      Long parent = f.getParentId();
      while (parent != null) {
        if (ids.contains(parent))
          return false;
        parent = drive.requireOwned(user, parent).getParentId();
      }
      return true;
    }).toList();
  }

  private String copyName(User user, Long parent, String original) {
    int dot = original.lastIndexOf('.');
    boolean hasExtension = dot > 0 && original.length() - dot <= 17;
    String extension = hasExtension ? original.substring(dot) : "";
    String base = hasExtension ? original.substring(0, dot) : original;
    for (int n = 0; n < 1000; n++) {
      String suffix = n == 0 ? "" : " - 副本" + (n == 1 ? "" : " (" + n + ")");
      int maximum = 255 - suffix.length() - extension.length();
      String name = n == 0 ? original
          : base.substring(0, Math.min(base.length(), Math.max(0, maximum))) + suffix + extension;
      try {
        drive.requireAvailableName(user.getId(), parent, name, null);
        return name;
      } catch (DriveFailure e) {
        if (!"NAME_CONFLICT".equals(e.code()))
          throw e;
      }
    }
    throw new DriveFailure(HttpStatus.CONFLICT, "NAME_CONFLICT", "No available copy name");
  }

  static String category(String name) {
    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif").contains(ext))
      return "图片";
    if (Set.of("mp4", "mkv", "mov", "avi", "webm", "m4v", "flv").contains(ext))
      return "视频";
    if (Set.of("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus").contains(ext))
      return "音频";
    if (Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "odt").contains(ext))
      return "文档";
    return "其他";
  }

  private record Group(Long parentId, String name) {
  }

  private static DriveFailure error(String code, String message) {
    return new DriveFailure(HttpStatus.BAD_REQUEST, code, message);
  }
}
