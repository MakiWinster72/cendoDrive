package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.user.User;
import com.cendodrive.storage.FileStorage;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DriveServiceTest {
    @Mock DriveFileRepository files;
    @Mock FileStorage storage;
    @Mock User user;
    DriveService service;

    @BeforeEach void setup() {
        service = new DriveService(files, storage, "/tmp/cendodrive-test-storage");
        lenient().when(user.getId()).thenReturn(7L);
    }

    @Test void uploadsAndPersistsFastDfsFileId() throws Exception {
        var upload = new MockMultipartFile("file", "hello.txt", "text/plain", "hello".getBytes());
        when(storage.upload(any(), eq(5L), eq("txt"))).thenReturn("group1/M00/hello.txt");
        when(files.saveAndFlush(any())).thenAnswer(invocation -> {
            DriveFile file = invocation.getArgument(0);
            assertEquals("fastdfs", file.getStorageBackend());
            assertEquals("group1/M00/hello.txt", file.getStorageKey());
            assertEquals(5, file.getSize());
            org.springframework.test.util.ReflectionTestUtils.setField(file, "id", 12L);
            org.springframework.test.util.ReflectionTestUtils.setField(file, "updatedAt", java.time.LocalDateTime.now());
            return file;
        });
        assertEquals("12", service.upload(user, upload, null).id());
        verify(storage).upload(any(), eq(5L), eq("txt"));
    }

    @Test void listsOnlyTheAuthenticatedUsersRoot() {
        when(files.findAllByOwnerIdAndParentIdIsNullOrderByKindAscNameAsc(7L)).thenReturn(List.of());
        assertTrue(service.list(user, null).isEmpty());
        verify(files).findAllByOwnerIdAndParentIdIsNullOrderByKindAscNameAsc(7L);
    }

    @Test void rejectsDuplicateFolderNamesAtRoot() {
        when(files.existsByOwnerIdAndParentIdIsNullAndNameIgnoreCase(7L, "工作")).thenReturn(true);
        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.createFolder(user, new CreateFolderRequest(" 工作 ", null)));
        assertEquals("NAME_CONFLICT", error.code());
        verify(files, never()).saveAndFlush(any());
    }

    @Test void rejectsMovingFolderIntoItsDescendant() {
        DriveFile source = mock(DriveFile.class);
        DriveFile child = mock(DriveFile.class);
        when(source.isFolder()).thenReturn(true);
        when(child.isFolder()).thenReturn(true);
        when(child.getParentId()).thenReturn(1L);
        when(files.findByIdAndOwnerId(1L, 7L)).thenReturn(Optional.of(source));
        when(files.findByIdAndOwnerId(2L, 7L)).thenReturn(Optional.of(child));
        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.move(user, 1L, new MoveRequest(2L)));
        assertEquals("INVALID_MOVE", error.code());
        verify(files, never()).saveAndFlush(any());
    }

    @Test void hidesFilesOwnedByAnotherUser() {
        when(files.findByIdAndOwnerId(99L, 7L)).thenReturn(Optional.empty());
        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.rename(user, 99L, new RenameRequest("new.txt")));
        assertEquals("FILE_NOT_FOUND", error.code());
    }
}
