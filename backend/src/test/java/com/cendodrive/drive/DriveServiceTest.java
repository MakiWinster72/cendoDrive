package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.user.User;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.storage.StorageCleanupService;
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
    @Mock FileQuotaService quota;
    @Mock StorageCleanupService storageCleanup;
    DriveService service;

    @BeforeEach void setup() {
        service = new DriveService(files, storage, "/tmp/cendodrive-test-storage", quota, storageCleanup);
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
        when(files.findAllByOwnerIdAndParentIdIsNullAndDeletedAtIsNullOrderByKindAscNameAsc(7L)).thenReturn(List.of());
        assertTrue(service.list(user, null).isEmpty());
        verify(files).findAllByOwnerIdAndParentIdIsNullAndDeletedAtIsNullOrderByKindAscNameAsc(7L);
    }

    @Test void rejectsDuplicateFolderNamesAtRoot() {
        when(files.existsByOwnerIdAndParentIdIsNullAndDeletedAtIsNullAndNameIgnoreCase(7L, "工作")).thenReturn(true);
        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.createFolder(user, new CreateFolderRequest(" 工作 ", null)));
        assertEquals("NAME_CONFLICT", error.code());
        verify(files, never()).saveAndFlush(any());
    }

    @Test void rejectsMovingFolderIntoItsDescendant() {
        DriveFile source = mock(DriveFile.class);
        DriveFile child = mock(DriveFile.class);
        when(source.getId()).thenReturn(1L);
        when(source.getParentId()).thenReturn(null);
        when(child.getId()).thenReturn(2L);
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

    @Test void movesSelectedItemToTrash() {
        DriveFile file = mock(DriveFile.class);
        when(file.getId()).thenReturn(1L);
        when(file.getParentId()).thenReturn(null);
        when(file.getName()).thenReturn("资料");
        when(file.getKind()).thenReturn("folder");
        when(file.getUpdatedAt()).thenReturn(java.time.LocalDateTime.now());
        when(files.findByIdAndOwnerId(1L, 7L)).thenReturn(Optional.of(file));
        when(files.saveAllAndFlush(anyList())).thenAnswer(call -> call.getArgument(0));
        service.trash(user, new FileIdsRequest(List.of(1L)));
        verify(file).moveToTrash();
    }

    @Test void refusesToPermanentlyDeleteActiveItem() {
        DriveFile file = mock(DriveFile.class);
        when(files.findByIdAndOwnerId(1L, 7L)).thenReturn(Optional.of(file));
        DriveFailure error = assertThrows(DriveFailure.class,
                () -> service.deleteForever(user, new FileIdsRequest(List.of(1L))));
        assertEquals("NOT_IN_TRASH", error.code());
        verify(files, never()).deleteAll(anyList());
    }

    @Test void trashListOnlyShowsTopLevelDeletedItems() {
        DriveFile parent = mock(DriveFile.class);
        DriveFile child = mock(DriveFile.class);
        when(parent.getId()).thenReturn(1L);
        when(parent.getName()).thenReturn("父目录");
        when(parent.getKind()).thenReturn("folder");
        when(parent.getUpdatedAt()).thenReturn(java.time.LocalDateTime.now());
        when(parent.getDeletedAt()).thenReturn(java.time.LocalDateTime.now());
        when(child.getId()).thenReturn(2L);
        when(child.getParentId()).thenReturn(1L);
        when(files.findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(7L)).thenReturn(List.of(parent, child));
        List<FileResponse> result = service.listTrash(user);
        assertEquals(List.of("1"), result.stream().map(FileResponse::id).toList());
    }
}
