package com.cendodrive.upload;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.drive.DriveService;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.upload.UploadDtos.ChunkResponse;
import com.cendodrive.upload.UploadDtos.InitRequest;
import com.cendodrive.upload.UploadDtos.InitResponse;
import com.cendodrive.upload.UploadDtos.StatusResponse;
import com.cendodrive.upload.UploadStateStore.UploadMeta;
import com.cendodrive.user.User;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadService {
    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern UPLOAD_ID_HEX = Pattern.compile(ChunkStorage.UPLOAD_ID_PATTERN);
    /** 单个会话最多 10000 片，同时用于约束 totalSize 的上界，避免算术溢出。 */
    private static final int MAX_TOTAL_CHUNKS = 10000;
    public static final String STATUS_UPLOADING = "UPLOADING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private final UploadStateStore state;
    private final ChunkStorage chunks;
    private final DriveService drive;
    private final FileStorage fastDfs;
    private final long chunkSize;
    private final long maxTotalSize;

    public UploadService(UploadStateStore state, ChunkStorage chunks, DriveService drive, FileStorage fastDfs,
                         @Value("${cendo.upload.chunk-size:5242880}") long chunkSize) {
        if (chunkSize <= 0) throw new IllegalArgumentException("cendo.upload.chunk-size must be positive");
        this.state = state;
        this.chunks = chunks;
        this.drive = drive;
        this.fastDfs = fastDfs;
        this.chunkSize = chunkSize;
        long limit = (long) MAX_TOTAL_CHUNKS * chunkSize;
        this.maxTotalSize = limit > 0 ? limit : Long.MAX_VALUE;
    }

    public InitResponse init(User user, InitRequest request) {
        String name = DriveService.validName(request.name());
        String fileHash = normalizedHash(request.fileHash(), "fileHash");
        long totalSize = request.totalSize();
        if (totalSize <= 0) fail(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "File is empty");
        if (totalSize > maxTotalSize)
            fail(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "File requires too many chunks");
        int totalChunks = (int) ((totalSize + chunkSize - 1) / chunkSize);
        String uploadId = uploadId(user.getId(), request.parentId(), name, totalSize, fileHash);
        Optional<Long> completed = state.findDone(uploadId);
        if (completed.isPresent() && stillOwned(user, completed.get()))
            return new InitResponse(uploadId, chunkSize, totalChunks, List.of(), STATUS_COMPLETED);
        drive.assertUploadTarget(user, request.parentId(), name, totalSize);
        state.saveMeta(new UploadMeta(uploadId, user.getId(), request.parentId(), name,
                totalSize, chunkSize, totalChunks, fileHash));
        return new InitResponse(uploadId, chunkSize, totalChunks,
                state.uploadedIndexes(uploadId, totalChunks), STATUS_UPLOADING);
    }

    public ChunkResponse chunk(User user, MultipartFile file, String uploadId, Integer index, String chunkHash) {
        UploadMeta meta = requireSession(user, uploadId);
        if (state.findDone(uploadId).isPresent())
            throw new DriveFailure(HttpStatus.CONFLICT, "UPLOAD_IN_PROGRESS", "Upload session has already been merged");
        if (index == null || index < 0 || index >= meta.totalChunks())
            throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_CHUNK", "Chunk index out of range");
        if (file == null)
            throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_CHUNK", "Chunk content is missing");
        String expectedHash = normalizedHash(chunkHash, "chunkHash");
        long expectedSize = chunkSize(meta, index);
        if (file.getSize() != expectedSize)
            fail(HttpStatus.BAD_REQUEST, "INVALID_CHUNK", "Chunk size mismatch");
        try (InputStream content = file.getInputStream()) {
            chunks.save(uploadId, index, content, expectedHash);
        } catch (ChunkChecksumException ex) {
            fail(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH", "Chunk checksum mismatch");
        } catch (IOException ex) {
            log.warn("Failed to store chunk {} of upload {}: {}", index, uploadId, ex.getMessage());
            fail(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Failed to store chunk");
        }
        state.markChunk(uploadId, index);
        List<Integer> received = state.uploadedIndexes(uploadId, meta.totalChunks());
        return new ChunkResponse(uploadId, received, received.size(), meta.totalChunks());
    }

    public StatusResponse status(User user, String uploadId) {
        String id = validUploadId(uploadId);
        UploadMeta meta = state.findMeta(id).orElseThrow(UploadService::sessionNotFound);
        requireOwner(user, meta);
        List<Integer> uploaded = state.uploadedIndexes(id, meta.totalChunks());
        List<Integer> missing = state.missingIndexes(id, meta.totalChunks());
        return new StatusResponse(id, STATUS_UPLOADING, meta.chunkSize(), meta.totalChunks(),
                uploaded, missing, uploaded.size(), meta.totalSize());
    }

    public FileResponse merge(User user, String uploadId) {
        String id = validUploadId(uploadId);
        FileResponse merged = findMerged(user, id);
        if (merged != null) return merged;
        UploadMeta meta = requireSession(user, id);
        String token = state.tryLock(id).orElse(null);
        if (token == null) {
            FileResponse raced = findMerged(user, id);
            if (raced != null) return raced;
            fail(HttpStatus.CONFLICT, "UPLOAD_IN_PROGRESS", "Merge already in progress");
        }
        try {
            FileResponse raced = findMerged(user, id);
            if (raced != null) return raced;
            List<Integer> missing = state.missingIndexes(id, meta.totalChunks());
            if (!missing.isEmpty())
                fail(HttpStatus.CONFLICT, "CHUNK_INCOMPLETE", "Missing " + missing.size() + " chunk(s)");
            drive.assertUploadTarget(user, meta.parentId(), meta.name(), meta.totalSize());
            String storageKey = uploadMerged(id, meta);
            FileResponse response = drive.persistUploaded(user, meta.parentId(), meta.name(),
                    meta.totalSize(), storageKey);
            state.markDone(id, Long.parseLong(response.id()));
            chunks.cleanup(id);
            state.clearSession(id);
            return response;
        } finally {
            state.releaseLock(id, token);
        }
    }

    private String uploadMerged(String uploadId, UploadMeta meta) {
        InputStream content = openChunks(uploadId, meta.totalChunks());
        MessageDigest digest = sha256();
        String storageKey;
        try {
            storageKey = fastDfs.upload(new DigestInputStream(content, digest), meta.totalSize(), extension(meta.name()));
        } catch (IOException ex) {
            log.warn("Storage failed while merging upload {}: {}", uploadId, ex.getMessage());
            throw new DriveFailure(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Storage unavailable while merging");
        } finally {
            closeQuietly(content);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(meta.fileHash())) {
            deleteQuietly(storageKey);
            // 无法定位坏片，丢弃全部进度让客户端重传，避免永久卡在合并失败。
            state.clearChunks(uploadId);
            fail(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH", "Merged file checksum mismatch");
        }
        return storageKey;
    }

    private InputStream openChunks(String uploadId, int totalChunks) {
        try {
            return chunks.openAll(uploadId, totalChunks);
        } catch (MissingChunkException ex) {
            state.clearChunk(uploadId, ex.index());
            throw new DriveFailure(HttpStatus.CONFLICT, "CHUNK_INCOMPLETE",
                    "Chunk " + ex.index() + " is missing, upload it again");
        } catch (IOException ex) {
            log.warn("Failed to open chunks of upload {}: {}", uploadId, ex.getMessage());
            throw new DriveFailure(HttpStatus.CONFLICT, "CHUNK_INCOMPLETE", "Failed to read stored chunks");
        }
    }

    private FileResponse findMerged(User user, String uploadId) {
        return state.findDone(uploadId).map(fileId -> drive.findOwned(user, fileId)).orElse(null);
    }

    private boolean stillOwned(User user, long fileId) {
        try {
            drive.findOwned(user, fileId);
            return true;
        } catch (DriveFailure ex) {
            return false;
        }
    }

    private UploadMeta requireSession(User user, String uploadId) {
        UploadMeta meta = state.findMeta(validUploadId(uploadId)).orElseThrow(UploadService::sessionNotFound);
        requireOwner(user, meta);
        return meta;
    }

    private static void requireOwner(User user, UploadMeta meta) {
        if (!Objects.equals(meta.ownerId(), user.getId())) throw sessionNotFound();
    }

    private static DriveFailure sessionNotFound() {
        return new DriveFailure(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "Upload session not found or expired");
    }

    private static String validUploadId(String uploadId) {
        if (uploadId == null || !UPLOAD_ID_HEX.matcher(uploadId).matches()) throw sessionNotFound();
        return uploadId;
    }

    private static String normalizedHash(String value, String field) {
        if (value == null || !SHA256_HEX.matcher(value).matches())
            throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid " + field);
        return value.toLowerCase(Locale.ROOT);
    }

    private static long chunkSize(UploadMeta meta, int index) {
        if (index < meta.totalChunks() - 1) return meta.chunkSize();
        return meta.totalSize() - (long) (meta.totalChunks() - 1) * meta.chunkSize();
    }

    private static String uploadId(Long ownerId, Long parentId, String name, long totalSize, String fileHash) {
        MessageDigest digest = sha256();
        String seed = ownerId + "|" + parentId + "|" + name + "|" + totalSize + "|" + fileHash;
        return HexFormat.of().formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        String extension = name.substring(dot + 1);
        return extension.matches("[A-Za-z0-9]{0,16}") ? extension : "";
    }

    private void deleteQuietly(String storageKey) {
        try {
            fastDfs.delete(storageKey);
        } catch (IOException ex) {
            log.warn("Failed to delete merged content {}: {}", storageKey, ex.getMessage());
        }
    }

    private static void closeQuietly(InputStream content) {
        if (content == null) return;
        try {
            content.close();
        } catch (IOException ex) {
            log.warn("Failed to close chunk stream: {}", ex.getMessage());
        }
    }

    private static void fail(HttpStatus status, String code, String message) {
        throw new DriveFailure(status, code, message);
    }
}
