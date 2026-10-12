package com.cendodrive.upload;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.drive.DriveService;
import com.cendodrive.drive.FileQuotaService;
import com.cendodrive.upload.UploadDtos.*;
import com.cendodrive.user.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {
    private static final String UPLOAD_ID = "a".repeat(32);
    private static final String CONTENT = "abcdefgh";
    private static final String HASH = hashOf(CONTENT);

    @TempDir Path root;
    @Mock UploadSessionRepository sessions;
    @Mock FileQuotaService quota;
    @Mock DriveService drive;
    @Mock User user;
    UploadService service;

    @BeforeEach void setup() {
        service = new UploadService(sessions, quota, drive, root.toString(), 24);
        lenient().when(user.getId()).thenReturn(7L);
    }

    @Test void initCreatesSessionWithRequestedChunkLayout() {
        InitResponse response = service.init(user, request());
        assertEquals(4, response.chunkSize());
        assertEquals(2, response.totalChunks());
        assertEquals(32, response.uploadId().length());
        verify(drive).validateUploadTarget(user, "movie.mp4", null);
        verify(quota).check(user, 8L, null);
        verify(sessions).saveAndFlush(argThat(s -> s.getId().equals(response.uploadId())
                && s.getFileHash().equals(HASH) && s.getTotalChunks() == 2));
    }

    @Test void initReusesMatchingSession() {
        when(sessions.findAllByOwnerIdAndFileHashAndExpiresAtAfter(eq(7L), eq(HASH), any()))
                .thenReturn(List.of(session()));
        assertEquals(UPLOAD_ID, service.init(user, request()).uploadId());
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test void initRejectsInvalidChunkLayout() {
        DriveFailure error = assertThrows(DriveFailure.class, () ->
                service.init(user, new InitRequest("movie.mp4", 8, HASH, null, 4, 3)));
        assertEquals("INVALID_UPLOAD", error.code());
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test void chunkWritesContentAndStatusReportsProgress() throws Exception {
        when(sessions.lock(UPLOAD_ID, 7L)).thenReturn(Optional.of(session()));
        service.chunk(user, UPLOAD_ID, 0, 2, HASH, part("abcd"));
        assertEquals(List.of(0), service.status(user, UPLOAD_ID).uploadedChunks());
        assertArrayEquals("abcd".getBytes(), Files.readAllBytes(root.resolve("7").resolve(UPLOAD_ID).resolve("0.part")));
    }

    @Test void chunkRejectsWrongSizeWithoutWriting() throws Exception {
        when(sessions.lock(UPLOAD_ID, 7L)).thenReturn(Optional.of(session()));
        DriveFailure error = assertThrows(DriveFailure.class, () ->
                service.chunk(user, UPLOAD_ID, 0, 2, HASH, part("abc")));
        assertEquals("INVALID_CHUNK", error.code());
        assertEquals(List.of(), service.status(user, UPLOAD_ID).uploadedChunks());
    }

    @Test void statusRejectsExpiredSession() {
        UploadSession expired = new UploadSession(UPLOAD_ID, 7L, null, "movie.mp4", 8, HASH, 4, 2,
                LocalDateTime.now(Clock.systemUTC()).minusHours(1));
        when(sessions.lock(UPLOAD_ID, 7L)).thenReturn(Optional.of(expired));
        DriveFailure error = assertThrows(DriveFailure.class, () -> service.status(user, UPLOAD_ID));
        assertEquals("UPLOAD_EXPIRED", error.code());
    }

    @Test void mergeRejectsIncompleteUpload() throws Exception {
        when(sessions.lock(UPLOAD_ID, 7L)).thenReturn(Optional.of(session()));
        DriveFailure error = assertThrows(DriveFailure.class, () ->
                service.merge(user, new MergeRequest(UPLOAD_ID, HASH)));
        assertEquals("INCOMPLETE_UPLOAD", error.code());
        verify(drive, never()).uploadStream(any(), any(), anyLong(), anyString(), any(), anyString());
    }

    @Test void mergeUploadsCompleteContentAndMarksSessionDone() throws Exception {
        UploadSession current = session();
        when(sessions.lock(UPLOAD_ID, 7L)).thenReturn(Optional.of(current));
        FileResponse saved = new FileResponse("12", "movie.mp4", "file", 8, null, null, null);
        when(drive.uploadStream(eq(user), any(), eq(8L), eq("movie.mp4"), isNull(), eq(UPLOAD_ID)))
                .thenAnswer(call -> {
                    assertArrayEquals(CONTENT.getBytes(), ((java.io.InputStream) call.getArgument(1)).readAllBytes());
                    return saved;
                });
        service.chunk(user, UPLOAD_ID, 0, 2, HASH, part("abcd"));
        service.chunk(user, UPLOAD_ID, 1, 2, HASH, part("efgh"));
        assertEquals("12", service.merge(user, new MergeRequest(UPLOAD_ID, HASH)).id());
        assertEquals(12L, current.getResultFileId());
        verify(sessions).saveAndFlush(current);
        assertFalse(Files.exists(root.resolve("7").resolve(UPLOAD_ID)));
    }

    private static InitRequest request() { return new InitRequest("movie.mp4", 8, HASH, null, 4, 2); }
    private static UploadSession session() {
        return new UploadSession(UPLOAD_ID, 7L, null, "movie.mp4", 8, HASH, 4, 2,
                LocalDateTime.now(Clock.systemUTC()).plusHours(1));
    }
    private static MockMultipartFile part(String content) {
        return new MockMultipartFile("chunk", content.getBytes());
    }
    private static String hashOf(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content.getBytes()));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
