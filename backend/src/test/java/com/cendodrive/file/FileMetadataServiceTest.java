package com.cendodrive.file;

import com.cendodrive.file.FileDtos.RegisterStoredFileCommand;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileMetadataServiceTest {
    @Mock FileEntryRepository entries;
    @Mock UserRepository users;
    FileMetadataService metadata;
    User user;
    @BeforeEach void setup() {
        user = new User("alice", "hash", "Alice");
        when(users.findByIdForUpdate(1)).thenReturn(Optional.of(user));
        metadata = new FileMetadataService(entries, new FileAccessService(entries, users), new FileNamePolicy());
    }
    private RegisterStoredFileCommand command(long size, String key) {
        return new RegisterStoredFileCommand(0, "report.pdf", size, "FASTDFS", key, "application/pdf", null, "upload-1");
    }
    @Test void registersOnceEvenAfterRenameAndMove() {
        when(entries.saveAndFlush(any())).thenAnswer(inv -> {
            FileEntry entry = inv.getArgument(0);
            ReflectionTestUtils.setField(entry, "id", 5L);
            entry.created();
            return entry;
        });
        var first = metadata.registerStoredFile(1, command(100, "group1/object"));
        var captor = org.mockito.ArgumentCaptor.forClass(FileEntry.class);
        verify(entries).saveAndFlush(captor.capture());
        FileEntry saved = captor.getValue();
        saved.rename(new FileNamePolicy().normalize("renamed.pdf"));
        saved.moveTo(42);
        when(entries.findByUserIdAndIngestKey(1, "upload-1")).thenReturn(Optional.of(saved));
        var retry = metadata.registerStoredFile(1, command(100, "group1/object"));
        assertEquals(first.id(), retry.id());
        assertEquals("renamed.pdf", retry.name());
        assertEquals(100, user.getStorageUsed());
        verify(entries, times(1)).saveAndFlush(any());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(FileBusinessException.class,
                () -> metadata.registerStoredFile(1, command(101, "group1/object"))).code());
    }
    @Test void rejectsInsufficientQuotaWithoutInsertingOrCharging() {
        assertEquals("STORAGE_QUOTA_EXCEEDED", assertThrows(FileBusinessException.class,
                () -> metadata.registerStoredFile(1, command(Long.MAX_VALUE, "group1/object"))).code());
        assertEquals(0, user.getStorageUsed());
        verify(entries, never()).saveAndFlush(any());
    }
    @Test void rejectsInvalidStorageMetadata() {
        assertEquals("INVALID_METADATA", assertThrows(FileBusinessException.class,
                () -> metadata.registerStoredFile(1, command(-1, "group1/object"))).code());
        assertThrows(FileBusinessException.class, () -> metadata.registerStoredFile(1, command(0, " ")));
        assertThrows(FileBusinessException.class, () -> metadata.registerStoredFile(1, null));
        verify(entries, never()).saveAndFlush(any());
    }
    @Test void rejectsForeignParentWithoutCharging() {
        var command = new RegisterStoredFileCommand(99, "report.pdf", 100, "FASTDFS", "group1/object", null, null, "upload-1");
        assertEquals("FILE_NOT_FOUND", assertThrows(FileBusinessException.class,
                () -> metadata.registerStoredFile(1, command)).code());
        assertEquals(0, user.getStorageUsed());
        verify(entries, never()).saveAndFlush(any());
    }
}
