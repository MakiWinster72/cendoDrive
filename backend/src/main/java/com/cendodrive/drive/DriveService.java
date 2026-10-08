package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import com.cendodrive.storage.FileStorage;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DriveService {
    private static final Logger log = LoggerFactory.getLogger(DriveService.class);
    private static final int MAX_NAME_LENGTH = 200;
    private final DriveFileRepository files;
    private final UserRepository users;
    private final Path storageRoot;
    private final FileStorage fastDfs;
    public DriveService(DriveFileRepository files, UserRepository users, FileStorage fastDfs,
                        @Value("${cendo.storage.root:./storage}") String storageRoot) {
        this.files = files;
        this.users = users;
        this.fastDfs = fastDfs;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public FileResponse upload(User user, MultipartFile content, Long parentId) throws IOException {
        if (content == null || content.isEmpty()) fail(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "File is empty");
        String name = validName(Objects.requireNonNullElse(content.getOriginalFilename(), ""));
        if (parentId != null) requireFolder(user, parentId);
        requireAvailableName(user.getId(), parentId, name, null);
        requireQuota(user, content.getSize());
        String extension = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1);
        if (!extension.matches("[A-Za-z0-9]{0,16}")) extension = "";
        String key;
        try (var input = content.getInputStream()) {
            key = fastDfs.upload(input, content.getSize(), extension);
        }
        FileResponse response;
        try {
            response = FileResponse.from(files.saveAndFlush(DriveFile.uploaded(user.getId(), parentId, name, content.getSize(), key)));
        } catch (RuntimeException ex) {
            try { fastDfs.delete(key); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
        syncStorageUsed(user.getId());
        return response;
    }

    /** 分片上传场景：在真正传输前校验父目录、名称冲突与配额，返回规范化后的文件名。 */
    public String assertUploadTarget(User user, Long parentId, String rawName, long size) {
        String name = validName(rawName);
        if (parentId != null) requireFolder(user, parentId);
        requireAvailableName(user.getId(), parentId, name, null);
        requireQuota(user, size);
        return name;
    }

    @Transactional(readOnly = true)
    public FileResponse findOwned(User user, Long id) {
        return FileResponse.from(requireOwned(user, id));
    }

    /**
     * 分片合并完成后落库：内容已写入存储，任何失败路径（校验、flush、提交）都必须回收该内容，
     * 否则会留下不计入配额、也无法通过回收站清理的孤儿文件。
     */
    @Transactional
    public FileResponse persistUploaded(User user, Long parentId, String rawName, long size, String storageKey) {
        boolean synchronizations = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizations) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) deleteStoredKey(storageKey);
                }
            });
        }
        try {
            String name = validName(rawName);
            if (parentId != null) requireFolder(user, parentId);
            requireAvailableName(user.getId(), parentId, name, null);
            requireQuota(user, size);
            DriveFile saved = files.saveAndFlush(DriveFile.uploaded(user.getId(), parentId, name, size, storageKey));
            syncStorageUsed(user.getId());
            return FileResponse.from(saved);
        } catch (RuntimeException ex) {
            if (!synchronizations) deleteStoredKey(storageKey);
            throw ex;
        }
    }

    private void deleteStoredKey(String storageKey) {
        try {
            fastDfs.delete(storageKey);
        } catch (IOException ex) {
            log.warn("Failed to reclaim content {} after failed persistence: {}", storageKey, ex.getMessage());
        }
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
            requireAvailableName(user.getId(), file.getParentId(), DriveFile.displayName(file.getName()), file);
            file.restore();
        });
        return files.saveAllAndFlush(selected).stream().map(FileResponse::from).toList();
    }

    @Transactional
    public void deleteForever(User user, FileIdsRequest request) {
        List<DriveFile> selected = requireSelection(user, request);
        if (selected.stream().anyMatch(file -> !file.isDeleted()))
            fail(HttpStatus.CONFLICT, "NOT_IN_TRASH", "Only trashed items can be permanently deleted");
        List<DriveFile> removed = contentsOf(user, selected);
        files.deleteAll(selected);
        files.flush();
        deleteContents(removed);
        syncStorageUsed(user.getId());
    }

    @Transactional
    public void emptyTrash(User user) {
        List<DriveFile> trashed = files.findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(user.getId());
        List<DriveFile> removed = contentsOf(user, trashed);
        files.deleteAll(trashed);
        files.flush();
        deleteContents(removed);
        syncStorageUsed(user.getId());
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
        if ("fastdfs".equals(file.getStorageBackend())) {
            String key = file.getStorageKey();
            return new Download(file.getName(), output -> fastDfs.download(key, output), file.getSize());
        }
        if (!"local".equals(file.getStorageBackend())) fail(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", "Unknown storage backend");
        Path path = storageRoot.resolve(file.getStorageKey()).normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) fail(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND", "File content not found");
        return new Download(file.getName(), output -> Files.copy(path, output), file.getSize());
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
    private void requireQuota(User user, long size) {
        long used = files.sumFileSizeByOwnerId(user.getId());
        long remaining = user.getStorageLimit() - used;
        if (size < 0 || remaining < size)
            fail(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_QUOTA_EXCEEDED", "Storage quota exceeded");
    }
    private void syncStorageUsed(Long ownerId) {
        long used = files.sumFileSizeByOwnerId(ownerId);
        users.findById(ownerId).ifPresent(account -> {
            account.updateStorageUsed(used);
            users.save(account);
        });
    }
    private List<DriveFile> contentsOf(User user, List<DriveFile> roots) {
        Set<Long> ids = roots.stream().map(DriveFile::getId).collect(Collectors.toCollection(HashSet::new));
        List<DriveFile> all = files.findAllByOwnerId(user.getId());
        boolean expanded = true;
        while (expanded) {
            expanded = false;
            for (DriveFile file : all) {
                if (file.getParentId() != null && ids.contains(file.getParentId()) && ids.add(file.getId())) expanded = true;
            }
        }
        return all.stream().filter(file -> ids.contains(file.getId())).toList();
    }
    private void deleteContents(List<DriveFile> removed) {
        List<StoredContent> contents = removed.stream()
                .filter(file -> file.getStorageKey() != null && !file.getStorageKey().isBlank())
                .map(file -> new StoredContent(file.getId(), file.getStorageBackend(), file.getStorageKey()))
                .toList();
        if (contents.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deleteStoredContents(contents); }
            });
            return;
        }
        deleteStoredContents(contents);
    }
    private void deleteStoredContents(List<StoredContent> contents) {
        for (StoredContent content : contents) {
            try {
                if ("fastdfs".equals(content.backend())) {
                    fastDfs.delete(content.key());
                    continue;
                }
                if (!"local".equals(content.backend())) continue;
                Path path = storageRoot.resolve(content.key()).normalize();
                if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) {
                    log.warn("Skip deleting unsafe or missing content of file {}", content.id());
                    continue;
                }
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                log.warn("Failed to delete stored content of file {}: {}", content.id(), ex.getMessage());
            }
        }
    }
    private record StoredContent(Long id, String backend, String key) {}
    public static String validName(String raw) {
        if (raw == null) fail(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Invalid file name");
        String value = raw.trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")
                || value.length() > MAX_NAME_LENGTH)
            fail(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Invalid file name");
        return value;
    }
    private static void fail(HttpStatus status, String code, String message) { throw new DriveFailure(status, code, message); }
    public record Download(String name, org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body, long size) {}
}
