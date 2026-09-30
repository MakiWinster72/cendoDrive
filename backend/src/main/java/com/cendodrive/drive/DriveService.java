package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.user.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriveService {
    private final DriveFileRepository files;
    private final Path storageRoot;
    public DriveService(DriveFileRepository files, @Value("${cendo.storage.root:./storage}") String storageRoot) {
        this.files = files;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<FileResponse> list(User user, Long parentId) {
        if (parentId != null) requireFolder(user, parentId);
        var result = parentId == null
                ? files.findAllByOwnerIdAndParentIdIsNullAndDeletedAtIsNullOrderByKindAscNameAsc(user.getId())
                : files.findAllByOwnerIdAndParentIdAndDeletedAtIsNullOrderByKindAscNameAsc(user.getId(), parentId);
        return result.stream().map(FileResponse::from).toList();
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
        List<DriveFile> selected = requireSelection(user, request);
        selected.forEach(file -> {
            if (file.isDeleted()) fail(HttpStatus.CONFLICT, "ALREADY_IN_TRASH", "Item is already in trash");
            file.moveToTrash();
        });
        return files.saveAllAndFlush(selected).stream().map(FileResponse::from).toList();
    }

    @Transactional
    public List<FileResponse> restore(User user, FileIdsRequest request) {
        List<DriveFile> selected = requireSelection(user, request);
        selected.forEach(file -> {
            if (!file.isDeleted()) fail(HttpStatus.CONFLICT, "NOT_IN_TRASH", "Item is not in trash");
            if (file.getParentId() != null && requireOwned(user, file.getParentId()).isDeleted())
                fail(HttpStatus.CONFLICT, "PARENT_IN_TRASH", "Parent folder is still in trash");
            requireAvailableName(user.getId(), file.getParentId(), file.getName(), file);
            file.restore();
        });
        return files.saveAllAndFlush(selected).stream().map(FileResponse::from).toList();
    }

    @Transactional
    public void deleteForever(User user, FileIdsRequest request) {
        List<DriveFile> selected = requireSelection(user, request);
        if (selected.stream().anyMatch(file -> !file.isDeleted()))
            fail(HttpStatus.CONFLICT, "NOT_IN_TRASH", "Only trashed items can be permanently deleted");
        files.deleteAll(selected);
        files.flush();
    }

    @Transactional
    public void emptyTrash(User user) {
        files.deleteAll(files.findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(user.getId()));
        files.flush();
    }

    @Transactional
    public FileResponse createFolder(User user, CreateFolderRequest request) {
        String name = validName(request.name());
        if (request.parentId() != null) requireFolder(user, request.parentId());
        requireAvailableName(user.getId(), request.parentId(), name, null);
        return FileResponse.from(files.saveAndFlush(DriveFile.folder(user.getId(), request.parentId(), name)));
    }

    @Transactional
    public FileResponse rename(User user, Long id, RenameRequest request) {
        DriveFile file = requireActiveOwned(user, id);
        String name = validName(request.name());
        requireAvailableName(user.getId(), file.getParentId(), name, file);
        file.rename(name);
        return FileResponse.from(files.saveAndFlush(file));
    }

    @Transactional
    public FileResponse move(User user, Long id, MoveRequest request) {
        DriveFile file = requireActiveOwned(user, id);
        Long targetId = request.parentId();
        if (id.equals(targetId)) fail(HttpStatus.BAD_REQUEST, "INVALID_MOVE", "Cannot move an item into itself");
        if (targetId != null) {
            requireFolder(user, targetId);
            if (file.isFolder()) ensureNotDescendant(user, id, targetId);
        }
        requireAvailableName(user.getId(), targetId, file.getName(), file);
        file.moveTo(targetId);
        return FileResponse.from(files.saveAndFlush(file));
    }

    @Transactional(readOnly = true)
    public Download download(User user, Long id) {
        DriveFile file = requireActiveOwned(user, id);
        if (file.isFolder()) fail(HttpStatus.BAD_REQUEST, "FOLDER_NOT_DOWNLOADABLE", "Folders cannot be downloaded");
        if (file.getStorageKey() == null || file.getStorageKey().isBlank()) fail(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", "File content not found");
        Path path = storageRoot.resolve(file.getStorageKey()).normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) fail(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", "File content not found");
        return new Download(file.getName(), new FileSystemResource(path));
    }

    private DriveFile requireOwned(User user, Long id) {
        return files.findByIdAndOwnerId(id, user.getId()).orElseThrow(() ->
                new DriveFailure(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found"));
    }
    private DriveFile requireActiveOwned(User user, Long id) {
        DriveFile file = requireOwned(user, id);
        if (file.isDeleted()) fail(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
        return file;
    }
    private List<DriveFile> requireSelection(User user, FileIdsRequest request) {
        if (request.ids() == null || request.ids().isEmpty())
            throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_SELECTION", "Select at least one item");
        return request.ids().stream().distinct().map(id -> requireOwned(user, id)).toList();
    }
    private DriveFile requireFolder(User user, Long id) {
        DriveFile file = requireOwned(user, id);
        if (file.isDeleted()) fail(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
        if (!file.isFolder()) fail(HttpStatus.BAD_REQUEST, "NOT_A_FOLDER", "Target is not a folder");
        return file;
    }
    private void ensureNotDescendant(User user, Long sourceId, Long targetId) {
        Long cursor = targetId;
        while (cursor != null) {
            if (sourceId.equals(cursor)) fail(HttpStatus.BAD_REQUEST, "INVALID_MOVE", "Cannot move a folder into its descendant");
            cursor = requireFolder(user, cursor).getParentId();
        }
    }
    private void requireAvailableName(Long ownerId, Long parentId, String name, DriveFile current) {
        if (current != null && !current.isDeleted() && Objects.equals(current.getParentId(), parentId) && current.getName().equalsIgnoreCase(name)) return;
        boolean exists = parentId == null
                ? files.existsByOwnerIdAndParentIdIsNullAndDeletedAtIsNullAndNameIgnoreCase(ownerId, name)
                : files.existsByOwnerIdAndParentIdAndDeletedAtIsNullAndNameIgnoreCase(ownerId, parentId, name);
        if (exists) fail(HttpStatus.CONFLICT, "NAME_CONFLICT", "An item with the same name already exists");
    }
    private static String validName(String raw) {
        String value = raw.trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\"))
            fail(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Invalid file name");
        return value;
    }
    private static void fail(HttpStatus status, String code, String message) { throw new DriveFailure(status, code, message); }
    public record Download(String name, FileSystemResource resource) {}
}
