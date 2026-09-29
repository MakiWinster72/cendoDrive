package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.*;
import com.cendodrive.user.User;
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
    @Mock User user;
    DriveService service;

    @BeforeEach void setup() {
        service = new DriveService(files, "/tmp/cendodrive-test-storage");
        lenient().when(user.getId()).thenReturn(7L);
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
