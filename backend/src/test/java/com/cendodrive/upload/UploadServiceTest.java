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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {
    private static final String UPLOAD_ID = "a".repeat(64);
    private static final String LOCK_TOKEN = "token-1";
    private static final long CHUNK_SIZE = 5L;

    @Mock UploadStateStore state;
    @Mock ChunkStorage chunks;
    @Mock DriveService drive;
    @Mock FileStorage storage;
    @Mock User user;
    UploadService service;

    @BeforeEach void setup() {
        service = new UploadService(state, chunks, drive, storage, CHUNK_SIZE);
        lenient().when(user.getId()).thenReturn(7L);
    }

    @Test void initReturnsChunkLayoutAndAlreadyUploadedIndexes() {
        when(state.findDone(any())).thenReturn(Optional.empty());
        when(state.uploadedIndexes(any(), eq(6))).thenReturn(List.of(0, 2));

        InitResponse response = service.init(user, new InitRequest("movie.mp4", 3L, 26L, hashOf("movie")));

        assertEquals(CHUNK_SIZE, response.chunkSize());
        assertEquals(6, response.totalChunks());
        assertEquals(List.of(0, 2), response.uploadedIndexes());
        assertEquals(UploadService.STATUS_UPLOADING, response.status());
        verify(drive).assertUploadTarget(user, 3L, "movie.mp4", 26L);
        verify(state).saveMeta(any());
    }

    @Test void initReportsCompletedSessionWithoutRevalidatingTarget() {
        when(state.findDone(any())).thenReturn(Optional.of(12L));

        InitResponse response = service.init(user, new InitRequest("movie.mp4", null, 26L, hashOf("movie")));

        assertEquals(UploadService.STATUS_COMPLETED, response.status());
        assertTrue(response.uploadedIndexes().isEmpty());
        verify(drive, never()).assertUploadTarget(any(), any(), anyString(), anyLong());
        verify(state, never()).saveMeta(any());
    }

    @Test void initStartsFreshWhenTheCompletedFileWasDeleted() {
        when(state.findDone(any())).thenReturn(Optional.of(12L));
        when(drive.findOwned(user, 12L)).thenThrow(new DriveFailure(org.springframework.http.HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "gone"));
        when(state.uploadedIndexes(any(), eq(6))).thenReturn(List.of());

        InitResponse response = service.init(user, new InitRequest("movie.mp4", null, 26L, hashOf("movie")));

        assertEquals(UploadService.STATUS_UPLOADING, response.status());
        verify(state).saveMeta(any());
    }

    @Test void initRejectsMalformedFileHash() {
        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.init(user, new InitRequest("movie.mp4", null, 26L, "not-a-hash")));
        assertEquals("INVALID_INPUT", error.code());
        verify(state, never()).saveMeta(any());
    }

    @Test void initRejectsFilesRequiringTooManyChunks() {
        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.init(user, new InitRequest("movie.mp4", null, 50_001L, hashOf("movie"))));
        assertEquals("INVALID_INPUT", error.code());
        verify(state, never()).saveMeta(any());
    }

    @Test void initRejectsSizesThatWouldOverflowTheChunkCount() {
        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.init(user, new InitRequest("movie.mp4", null, Long.MAX_VALUE, hashOf("movie"))));
        assertEquals("INVALID_INPUT", error.code());
        verify(state, never()).saveMeta(any());
    }

    @Test void chunkRejectsIndexOutsideDeclaredRange() {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());

        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.chunk(user, part(new byte[5]), UPLOAD_ID, 6, hashOf("abcde")));

        assertEquals("INVALID_CHUNK", error.code());
        verify(state, never()).markChunk(any(), anyInt());
    }

    @Test void chunkRejectsSizeMismatchBeforeStoring() throws Exception {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());

        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.chunk(user, part(new byte[4]), UPLOAD_ID, 0, hashOf("abcd")));

        assertEquals("INVALID_CHUNK", error.code());
        verify(chunks, never()).save(any(), anyInt(), any(), anyString());
    }

    @Test void chunkRejectsSessionsOwnedByAnotherUser() {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(8L, 26L, 6)));

        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.chunk(user, part(new byte[5]), UPLOAD_ID, 0, hashOf("abcde")));

        assertEquals("UPLOAD_NOT_FOUND", error.code());
        verify(state, never()).markChunk(any(), anyInt());
    }

    @Test void chunkRefusesToWriteIntoAnAlreadyMergedSession() throws Exception {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.of(12L));

        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.chunk(user, part(new byte[5]), UPLOAD_ID, 0, hashOf("abcde")));

        assertEquals("UPLOAD_IN_PROGRESS", error.code());
        verify(chunks, never()).save(any(), anyInt(), any(), anyString());
        verify(state, never()).markChunk(any(), anyInt());
    }

    @Test void chunkKeepsIndexUnmarkedWhenChecksumFails() throws Exception {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());
        doThrow(new ChunkChecksumException("bad")).when(chunks).save(any(), anyInt(), any(), anyString());

        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.chunk(user, part(new byte[5]), UPLOAD_ID, 0, hashOf("abcde")));

        assertEquals("CHECKSUM_MISMATCH", error.code());
        verify(state, never()).markChunk(any(), anyInt());
    }

    @Test void chunkStoresContentThenMarksIndex() throws Exception {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());
        when(state.uploadedIndexes(UPLOAD_ID, 6)).thenReturn(List.of(0));

        ChunkResponse response = service.chunk(user, part(new byte[5]), UPLOAD_ID, 0, hashOf("abcde"));

        verify(chunks).save(eq(UPLOAD_ID), eq(0), any(), eq(hashOf("abcde")));
        verify(state).markChunk(UPLOAD_ID, 0);
        assertEquals(1, response.received());
        assertEquals(6, response.totalChunks());
        assertEquals(List.of(0), response.receivedIndexes());
    }

    @Test void statusReportsUploadedAndMissingIndexes() {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.uploadedIndexes(UPLOAD_ID, 6)).thenReturn(List.of(0, 1));
        when(state.missingIndexes(UPLOAD_ID, 6)).thenReturn(List.of(2, 3, 4, 5));

        StatusResponse response = service.status(user, UPLOAD_ID);

        assertEquals(UploadService.STATUS_UPLOADING, response.status());
        assertEquals(2, response.received());
        assertEquals(26L, response.totalSize());
        assertEquals(List.of(2, 3, 4, 5), response.missingIndexes());
    }

    @Test void statusRejectsExpiredSessions() {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.empty());

        DriveFailure error = assertThrows(DriveFailure.class, () -> service.status(user, UPLOAD_ID));

        assertEquals("UPLOAD_NOT_FOUND", error.code());
    }

    @Test void mergeRefusesIncompleteUploadsAndReleasesLock() throws Exception {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());
        when(state.tryLock(UPLOAD_ID)).thenReturn(Optional.of(LOCK_TOKEN));
        when(state.missingIndexes(UPLOAD_ID, 6)).thenReturn(List.of(5));

        DriveFailure error = assertThrows(DriveFailure.class, () -> service.merge(user, UPLOAD_ID));

        assertEquals("CHUNK_INCOMPLETE", error.code());
        verify(storage, never()).upload(any(), anyLong(), anyString());
        verify(state).releaseLock(UPLOAD_ID, LOCK_TOKEN);
        verify(chunks, never()).cleanup(any());
    }

    @Test void mergeClearsOnlyTheBitmapEntryWhenAChunkFileIsMissing() throws Exception {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());
        when(state.tryLock(UPLOAD_ID)).thenReturn(Optional.of(LOCK_TOKEN));
        when(state.missingIndexes(UPLOAD_ID, 6)).thenReturn(List.of());
        when(chunks.openAll(UPLOAD_ID, 6)).thenThrow(new MissingChunkException(4, "gone"));

        DriveFailure error = assertThrows(DriveFailure.class, () -> service.merge(user, UPLOAD_ID));

        assertEquals("CHUNK_INCOMPLETE", error.code());
        assertTrue(error.getMessage().contains("4"));
        verify(state).clearChunk(UPLOAD_ID, 4);
        verify(state).releaseLock(UPLOAD_ID, LOCK_TOKEN);
    }

    @Test void mergeReportsStorageFailureAsServiceUnavailable() throws Exception {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());
        when(state.tryLock(UPLOAD_ID)).thenReturn(Optional.of(LOCK_TOKEN));
        when(state.missingIndexes(UPLOAD_ID, 6)).thenReturn(List.of());
        when(chunks.openAll(UPLOAD_ID, 6)).thenReturn(new ByteArrayInputStream(new byte[26]));
        when(storage.upload(any(), anyLong(), anyString())).thenThrow(new IOException("fastdfs down"));

        DriveFailure error = assertThrows(DriveFailure.class, () -> service.merge(user, UPLOAD_ID));

        assertEquals("STORAGE_UNAVAILABLE", error.code());
        verify(state).releaseLock(UPLOAD_ID, LOCK_TOKEN);
    }

    @Test void mergeRejectsMergedContentWithWrongChecksumAndResetsProgress() throws Exception {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 5L, 1)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());
        when(state.tryLock(UPLOAD_ID)).thenReturn(Optional.of(LOCK_TOKEN));
        when(state.missingIndexes(UPLOAD_ID, 1)).thenReturn(List.of());
        when(chunks.openAll(UPLOAD_ID, 1)).thenReturn(new ByteArrayInputStream("tampered".getBytes(StandardCharsets.UTF_8)));
        consumesStreamAndReturns("group1/M00/tampered.mp4");

        DriveFailure error = assertThrows(DriveFailure.class, () -> service.merge(user, UPLOAD_ID));

        assertEquals("CHECKSUM_MISMATCH", error.code());
        verify(storage).delete("group1/M00/tampered.mp4");
        verify(state).clearChunks(UPLOAD_ID);
        verify(drive, never()).persistUploaded(any(), any(), anyString(), anyLong(), anyString());
        verify(chunks, never()).cleanup(any());
        verify(state).releaseLock(UPLOAD_ID, LOCK_TOKEN);
    }

    @Test void mergePersistsFileAndCleansSessionOnSuccess() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        UploadMeta meta = new UploadMeta(UPLOAD_ID, 7L, null, "movie.mp4", content.length, CHUNK_SIZE, 1, hashOf(content));
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());
        when(state.tryLock(UPLOAD_ID)).thenReturn(Optional.of(LOCK_TOKEN));
        when(state.missingIndexes(UPLOAD_ID, 1)).thenReturn(List.of());
        when(chunks.openAll(UPLOAD_ID, 1)).thenReturn(new ByteArrayInputStream(content));
        consumesStreamAndReturns("group1/M00/merged.mp4");
        FileResponse saved = new FileResponse("12", "movie.mp4", "file", 5, null, "2026-10-08T00:00:00Z", null);
        when(drive.persistUploaded(user, null, "movie.mp4", 5L, "group1/M00/merged.mp4")).thenReturn(saved);

        assertEquals("12", service.merge(user, UPLOAD_ID).id());

        verify(state).markDone(UPLOAD_ID, 12L);
        verify(chunks).cleanup(UPLOAD_ID);
        verify(state).clearSession(UPLOAD_ID);
        verify(state).releaseLock(UPLOAD_ID, LOCK_TOKEN);
    }

    @Test void mergeIsIdempotentAfterCompletion() throws Exception {
        FileResponse saved = new FileResponse("12", "movie.mp4", "file", 5, null, "2026-10-08T00:00:00Z", null);
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.of(12L));
        when(drive.findOwned(user, 12L)).thenReturn(saved);

        assertEquals("12", service.merge(user, UPLOAD_ID).id());

        verify(state, never()).tryLock(any());
        verify(storage, never()).upload(any(), anyLong(), anyString());
    }

    @Test void mergeRefusesConcurrentMergeOfTheSameSession() {
        when(state.findMeta(UPLOAD_ID)).thenReturn(Optional.of(meta(7L, 26L, 6)));
        when(state.findDone(UPLOAD_ID)).thenReturn(Optional.empty());
        when(state.tryLock(UPLOAD_ID)).thenReturn(Optional.empty());

        DriveFailure error = assertThrows(DriveFailure.class, () -> service.merge(user, UPLOAD_ID));

        assertEquals("UPLOAD_IN_PROGRESS", error.code());
        verify(state, never()).releaseLock(any(), any());
    }

    private static UploadMeta meta(Long ownerId, long totalSize, int totalChunks) {
        return new UploadMeta(UPLOAD_ID, ownerId, null, "movie.mp4", totalSize, CHUNK_SIZE, totalChunks, hashOf("merge"));
    }

    private static MockMultipartFile part(byte[] content) {
        return new MockMultipartFile("file", "chunk", "application/octet-stream", content);
    }

    /** 模拟真实存储读取合并流，使 DigestInputStream 能算出一致的整文件摘要。 */
    private void consumesStreamAndReturns(String storageKey) throws Exception {
        when(storage.upload(any(), anyLong(), anyString())).thenAnswer(invocation -> {
            InputStream input = invocation.getArgument(0);
            input.transferTo(OutputStream.nullOutputStream());
            return storageKey;
        });
    }

    private static String hashOf(String value) {
        return hashOf(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hashOf(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
