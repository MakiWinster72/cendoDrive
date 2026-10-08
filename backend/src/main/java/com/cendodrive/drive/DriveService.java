package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.user.User;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.index.AiIndexTaskService;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriveService {
  private final DriveFileRepository files;
  private final Path storageRoot;
  private final FileStorage fastDfs;
  private final FileQuotaService quota;
  private final AiIndexTaskService indexTasks;
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DriveService.class);

  public DriveService(DriveFileRepository files, FileStorage fastDfs,
      @Value("${cendo.storage.root:./storage}") String storageRoot, FileQuotaService quota,
      AiIndexTaskService indexTasks) {
    this.files = files;
    this.quota = quota;
    this.fastDfs = fastDfs;
    this.indexTasks = indexTasks;
    this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
  }

  @Transactional(rollbackFor = IOException.class)
  public FileResponse upload(User user, MultipartFile content, Long parentId) throws IOException {
    if (content == null || content.isEmpty())
      fail(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "File is empty");
    try (var input = content.getInputStream()) {
      return uploadStream(user, input, content.getSize(), content.getOriginalFilename(), parentId, null, true);
    }
  }

  @Transactional(rollbackFor = IOException.class)
  public FileResponse uploadStream(User user, java.io.InputStream input, long size, String rawName,
      Long parentId, String uploadId, boolean createIndexTask) throws IOException {
    User locked = quota.check(user, size, uploadId);
    String name = validateUploadTarget(user, rawName, parentId);
    String extension = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1);
    if (!extension.matches("[A-Za-z0-9]{0,16}")) extension = "";
    String key = fastDfs.upload(input, size, extension);
    boolean synchronizedCleanup = org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive();
    if (synchronizedCleanup) onRollback(() -> deleteStorage("fastdfs", key));
    try {
      FileResponse result = registerUploadedFile(user, parentId, name, size, key, createIndexTask);
      quota.refresh(locked);
      return result;
    } catch (RuntimeException ex) {
      if (!synchronizedCleanup) deleteStorage("fastdfs", key);
      throw ex;
    }
  }

  @Transactional(readOnly = true)
  public DriveFile shareableFile(User user, Long id) {
    DriveFile file = requireActiveOwned(user, id);
    if (file.isFolder())
      fail(HttpStatus.BAD_REQUEST, "FOLDER_NOT_SHAREABLE", "Folder sharing is not supported");
    Set<Long> visited = new HashSet<>();
    visited.add(id);
    Long parent = file.getParentId();
    while (parent != null) {
      if (!visited.add(parent))
        fail(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
      parent = requireFolder(user, parent).getParentId();
    }
    return file;
  }

  @Transactional(rollbackFor = IOException.class)
  public FileResponse saveSharedFile(User recipient, User owner, Long fileId, Long parentId) throws IOException {
    DriveFile source = shareableFile(owner, fileId);
    String name = validateUploadTarget(recipient, source.getName(), parentId);
    // Lock and check before copying; uploadStream rechecks the actual size under the same lock.
    quota.check(recipient, source.getSize(), null);
    // Stream via a temporary file: never retain the complete shared content in heap memory.
    Path temp = Files.createTempFile("cendo-share-", ".tmp");
    try {
      Download content = download(owner, fileId);
      try (var output = Files.newOutputStream(temp)) {
        content.body().writeTo(output);
      }
      long size = Files.size(temp);
      if (size != content.size())
        throw new IOException("Shared content size mismatch");
      try (var input = Files.newInputStream(temp)) {
        return uploadStream(recipient, input, size, name, parentId, null, false);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Transactional(readOnly = true)
  public String validateUploadTarget(User user, String rawName, Long parentId) {
    String name = validName(rawName);
    if (parentId != null) requireFolder(user, parentId);
    requireAvailableName(user.getId(), parentId, name, null);
    return name;
  }

  @Transactional(readOnly = true)
  public FileResponse metadata(User user, Long id) { return FileResponse.from(requireActiveOwned(user, id)); }

  private FileResponse registerUploadedFile(User user, Long parentId, String name, long size, String storageKey,
      boolean createIndexTask) {
    DriveFile file = DriveFile.uploaded(user.getId(), parentId, name, size, storageKey);
    if (createIndexTask) file.enableIndexing();
    file=files.saveAndFlush(file);
    if (createIndexTask) indexTasks.enqueueUpsert(file);
    return FileResponse.from(file);
  }

  @Transactional(readOnly = true)
  public List<FileResponse> list(User user, Long parentId) {
    if (parentId != null)
      requireFolder(user, parentId);
    var result = parentId == null
        ? files.findAllByOwnerIdAndParentIdIsNullAndDeletedAtIsNullOrderByKindAscNameAsc(user.getId())
        : files.findAllByOwnerIdAndParentIdAndDeletedAtIsNullOrderByKindAscNameAsc(user.getId(), parentId);
    boolean showHidden = parentId != null && hiddenTree(user, requireActiveOwned(user, parentId));
    return result.stream().filter(f -> showHidden || !f.isHidden()).map(FileResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public List<FileResponse> listTrash(User user) {
    List<DriveFile> deleted = files.findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(user.getId());
    Set<Long> deletedIds = deleted.stream().map(DriveFile::getId).collect(Collectors.toSet());
    return deleted.stream()
        .filter(file -> file.getParentId() == null || !deletedIds.contains(file.getParentId()))
        .map(FileResponse::from).toList();
  }

  @Transactional
  public List<FileResponse> trash(User user, FileIdsRequest request) {
    quota.lock(user);
    List<DriveFile> selected = requireSelection(user, request);
    selected.forEach(file -> {
      requireActiveOwned(user, file.getId());
      if (file.isDeleted())
        fail(HttpStatus.CONFLICT, "ALREADY_IN_TRASH", "Item is already in trash");
      file.moveToTrash();
    });
    return files.saveAllAndFlush(selected).stream().map(FileResponse::from).toList();
  }

  @Transactional
  public List<FileResponse> restore(User user, FileIdsRequest request) {
    quota.lock(user);
    List<DriveFile> selected = requireSelection(user, request);
    selected.forEach(file -> {
      if (!file.isDeleted())
        fail(HttpStatus.CONFLICT, "NOT_IN_TRASH", "Item is not in trash");
      if (file.getParentId() != null && !activeTree(user, requireOwned(user, file.getParentId())))
        fail(HttpStatus.CONFLICT, "PARENT_IN_TRASH", "Parent folder is still in trash");
      requireAvailableName(user.getId(), file.getParentId(), file.getName(), file);
      file.restore();
    });
    return files.saveAllAndFlush(selected).stream().map(FileResponse::from).toList();
  }

  @Transactional
  public void deleteForever(User user, FileIdsRequest request) {
    quota.lock(user);
    List<DriveFile> selected = requireSelection(user, request);
    if (selected.stream().anyMatch(file -> !file.isDeleted()))
      fail(HttpStatus.CONFLICT, "NOT_IN_TRASH", "Only trashed items can be permanently deleted");
    purge(user, selected);
  }

  @Transactional
  public void emptyTrash(User user) {
    quota.lock(user);
    purge(user, files.findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(user.getId()));
  }

  private void purge(User user, List<DriveFile> roots) {
    User locked = quota.lock(user);
    List<DriveFile> doomed = subtree(user, roots, true);
    List<Runnable> deletions = doomed.stream().filter(f -> !f.isFolder() && f.getStorageKey() != null)
        .map(f -> (Runnable) () -> deleteStorage(f.getStorageBackend(), f.getStorageKey())).toList();
    // A bulk delete is safe with the self-referencing ON DELETE CASCADE constraint.
    files.deleteAllInBatch(doomed);
    files.flush();
    quota.refresh(locked);
    afterCommit(() -> deletions.forEach(Runnable::run));
  }

  List<DriveFile> subtree(User user, List<DriveFile> roots, boolean includeDeleted) {
    var children = files.findAllByOwnerId(user.getId()).stream()
        .filter(f -> f.getParentId() != null).collect(Collectors.groupingBy(DriveFile::getParentId));
    var result = new java.util.LinkedHashMap<Long, DriveFile>();
    var queue = new java.util.ArrayDeque<>(roots);
    while (!queue.isEmpty()) {
      DriveFile f = queue.removeFirst();
      if (!includeDeleted && f.isDeleted()) continue;
      if (result.putIfAbsent(f.getId(), f) != null) continue;
      queue.addAll(children.getOrDefault(f.getId(), List.of()));
    }
    return List.copyOf(result.values());
  }

  private void deleteStorage(String backend, String key) {
    try {
      if ("fastdfs".equals(backend)) fastDfs.delete(key);
      else if ("local".equals(backend)) {
        Path path = storageRoot.resolve(key).normalize();
        if (path.startsWith(storageRoot)) Files.deleteIfExists(path);
      }
    } catch (IOException | RuntimeException e) { LOG.warn("Storage cleanup failed for {}:{}", backend, key, e); }
  }

  private static void afterCommit(Runnable action) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) { action.run(); return; }
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
        new org.springframework.transaction.support.TransactionSynchronization() {
          @Override public void afterCommit() { action.run(); }
        });
  }

  private static void onRollback(Runnable action) {
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
        new org.springframework.transaction.support.TransactionSynchronization() {
          @Override public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) action.run();
          }
        });
  }

  @Transactional
  public FileResponse createFolder(User user, CreateFolderRequest request) {
    quota.lock(user);
    String name = validName(request.name());
    if (request.parentId() != null)
      requireFolder(user, request.parentId());
    requireAvailableName(user.getId(), request.parentId(), name, null);
    return FileResponse.from(files.saveAndFlush(DriveFile.folder(user.getId(), request.parentId(), name)));
  }

  @Transactional
  public FileResponse rename(User user, Long id, RenameRequest request) {
    quota.lock(user);
    DriveFile file = requireActiveOwned(user, id);
    String name = validName(request.name());
    requireAvailableName(user.getId(), file.getParentId(), name, file);
    file.rename(name);
    return FileResponse.from(files.saveAndFlush(file));
  }

  @Transactional
  public FileResponse move(User user, Long id, MoveRequest request) {
    quota.lock(user);
    DriveFile file = requireActiveOwned(user, id);
    Long targetId = request.parentId();
    if (id.equals(targetId))
      fail(HttpStatus.BAD_REQUEST, "INVALID_MOVE", "Cannot move an item into itself");
    if (targetId != null) {
      requireFolder(user, targetId);
      if (file.isFolder())
        ensureNotDescendant(user, id, targetId);
    }
    requireAvailableName(user.getId(), targetId, file.getName(), file);
    file.moveTo(targetId);
    return FileResponse.from(files.saveAndFlush(file));
  }

  @Transactional(readOnly = true)
  public Download download(User user, Long id) {
    DriveFile file = requireActiveOwned(user, id);
    if (file.isFolder())
      fail(HttpStatus.BAD_REQUEST, "FOLDER_NOT_DOWNLOADABLE", "Folders cannot be downloaded");
    if (file.getStorageKey() == null || file.getStorageKey().isBlank())
      fail(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", "File content not found");
    if ("fastdfs".equals(file.getStorageBackend())) {
      String key = file.getStorageKey();
      return new Download(file.getName(), output -> fastDfs.download(key, output), file.getSize());
    }
    if (!"local".equals(file.getStorageBackend()))
      fail(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", "Unknown storage backend");
    Path path = storageRoot.resolve(file.getStorageKey()).normalize();
    if (!path.startsWith(storageRoot) || !Files.isRegularFile(path))
      fail(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", "File content not found");
    return new Download(file.getName(), output -> Files.copy(path, output), file.getSize());
  }

  DriveFile requireOwned(User user, Long id) {
    return files.findByIdAndOwnerId(id, user.getId())
        .orElseThrow(() -> new DriveFailure(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found"));
  }

  DriveFile requireActiveOwned(User user, Long id) {
    DriveFile file = requireOwned(user, id);
    if (!activeTree(user, file)) fail(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
    return file;
  }

  boolean activeTree(User user, DriveFile file) {
    Set<Long> seen = new java.util.HashSet<>();
    while (file != null) {
      if (file.isDeleted() || !seen.add(file.getId())) return false;
      file = file.getParentId() == null ? null : requireOwned(user, file.getParentId());
    }
    return true;
  }

  boolean hiddenTree(User user, DriveFile file) {
    Set<Long> seen = new java.util.HashSet<>();
    while (file != null && seen.add(file.getId())) {
      if (file.isHidden()) return true;
      file = file.getParentId() == null ? null : requireOwned(user, file.getParentId());
    }
    return false;
  }

  List<DriveFile> requireSelection(User user, FileIdsRequest request) {
    if (request.ids() == null || request.ids().isEmpty())
      throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_SELECTION", "Select at least one item");
    return request.ids().stream().distinct().map(id -> requireOwned(user, id)).toList();
  }

  DriveFile requireFolder(User user, Long id) {
    DriveFile file = requireActiveOwned(user, id);
    if (file.isDeleted())
      fail(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
    if (!file.isFolder())
      fail(HttpStatus.BAD_REQUEST, "NOT_A_FOLDER", "Target is not a folder");
    return file;
  }

  void ensureNotDescendant(User user, Long sourceId, Long targetId) {
    Long cursor = targetId;
    Set<Long> seen = new java.util.HashSet<>();
    while (cursor != null) {
      if (!seen.add(cursor)) fail(HttpStatus.BAD_REQUEST, "INVALID_TREE", "Folder cycle detected");
      if (sourceId.equals(cursor))
        fail(HttpStatus.BAD_REQUEST, "INVALID_MOVE", "Cannot move a folder into its descendant");
      cursor = requireFolder(user, cursor).getParentId();
    }
  }

  void requireAvailableName(Long ownerId, Long parentId, String name, DriveFile current) {
    if (current != null && !current.isDeleted() && Objects.equals(current.getParentId(), parentId)
        && current.getName().equalsIgnoreCase(name))
      return;
    boolean exists = parentId == null
        ? files.existsByOwnerIdAndParentIdIsNullAndDeletedAtIsNullAndNameIgnoreCase(ownerId, name)
        : files.existsByOwnerIdAndParentIdAndDeletedAtIsNullAndNameIgnoreCase(ownerId, parentId, name);
    if (exists)
      fail(HttpStatus.CONFLICT, "NAME_CONFLICT", "An item with the same name already exists");
  }

  private static String validName(String raw) {
    String value = Objects.requireNonNullElse(raw, "").trim();
    if (value.isEmpty() || value.length() > 255 || value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\"))
      fail(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Invalid file name");
    return value;
  }

  private static void fail(HttpStatus status, String code, String message) {
    throw new DriveFailure(status, code, message);
  }

  public record Download(String name, org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body,
      long size) {
  }
}
