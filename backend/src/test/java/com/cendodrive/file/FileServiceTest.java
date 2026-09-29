package com.cendodrive.file;

import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {
    @Mock FileEntryRepository entries;
    @Mock UserRepository users;
    FileService service;
    FileAccessService access;
    final FileNamePolicy names = new FileNamePolicy();
    @BeforeEach void setup() {
        access = new FileAccessService(entries, users);
        service = new FileService(entries, access, names);
    }
    private void writable() {
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(new User("alice", "hash", "Alice")));
    }
    private FileEntry folder(long id, long parent, String name) {
        FileEntry entry = FileEntry.folder(1, parent, names.normalize(name));
        ReflectionTestUtils.setField(entry, "id", id);
        ReflectionTestUtils.setField(entry, "createdAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        ReflectionTestUtils.setField(entry, "updatedAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        return entry;
    }
    @Test void listsOnlyCurrentOwnersDirectChildrenWithStableSort() {
        when(entries.findByUserIdAndParentId(eq(1L), eq(0L), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(folder(2, 0, "资料")), inv.getArgument(2), 1));
        var result = service.list(1, 0, 0, 50);
        assertEquals("2", result.items().getFirst().id());
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(entries).findByUserIdAndParentId(eq(1L), eq(0L), captor.capture());
        assertTrue(captor.getValue().getSort().getOrderFor("entryType").isDescending());
        assertTrue(captor.getValue().getSort().getOrderFor("id").isAscending());
    }
    @Test void rejectsInaccessibleDirectoryBeforeListingChildren() {
        assertEquals("FILE_NOT_FOUND", assertThrows(FileBusinessException.class, () -> service.list(1, 99, 0, 50)).code());
        verify(entries, never()).findByUserIdAndParentId(anyLong(), anyLong(), any());
    }
    @Test void rejectsInvalidPagination() {
        assertThrows(FileBusinessException.class, () -> service.list(1, 0, -1, 50));
        assertThrows(FileBusinessException.class, () -> service.list(1, 0, 0, 101));
        assertThrows(FileBusinessException.class, () -> service.list(1, 0, Integer.MAX_VALUE, 100));
        verifyNoInteractions(entries);
    }
    @Test void createsNormalizedFolderWithoutStorageUsage() {
        writable();
        when(entries.saveAndFlush(any())).thenAnswer(inv -> {
            FileEntry entry = inv.getArgument(0);
            ReflectionTestUtils.setField(entry, "id", 5L);
            entry.created();
            return entry;
        });
        var result = service.createFolder(1, 0, "  资料  ");
        assertEquals("资料", result.name());
        assertEquals(0, result.sizeBytes());
        verify(entries).existsByUserIdAndParentIdAndNameKey(1, 0, "资料");
    }
    @Test void rejectsDuplicateNameAndForeignSource() {
        writable();
        when(entries.existsByUserIdAndParentIdAndNameKey(1, 0, "docs")).thenReturn(true);
        assertEquals("NAME_CONFLICT", assertThrows(FileBusinessException.class, () -> service.createFolder(1, 0, "DOCS")).code());
        assertEquals("FILE_NOT_FOUND", assertThrows(FileBusinessException.class, () -> service.rename(1, 99, "new")).code());
        verify(entries, never()).saveAndFlush(any());
    }
    @Test void renameAllowsCaseChangeAndExcludesSelf() {
        writable();
        when(entries.findByIdAndUserId(2, 1)).thenReturn(Optional.of(folder(2, 0, "Docs")));
        assertEquals("DOCS", service.rename(1, 2, "DOCS").name());
        verify(entries).existsByUserIdAndParentIdAndNameKeyAndIdNot(1, 0, "docs", 2);
    }
    @Test void rejectsMovingIntoDescendantOrSelf() {
        writable();
        when(entries.findByIdAndUserId(2, 1)).thenReturn(Optional.of(folder(2, 0, "A")));
        when(entries.findByIdAndUserId(3, 1)).thenReturn(Optional.of(folder(3, 2, "B")));
        assertEquals("INVALID_MOVE", assertThrows(FileBusinessException.class, () -> service.move(1, 2, 3)).code());
        assertEquals("INVALID_MOVE", assertThrows(FileBusinessException.class, () -> service.move(1, 2, 2)).code());
        verify(entries, never()).flush();
    }
    @Test void rejectsForeignTargetAndCorruptAncestorCycle() {
        writable();
        when(entries.findByIdAndUserId(2, 1)).thenReturn(Optional.of(folder(2, 0, "A")));
        assertEquals("FILE_NOT_FOUND", assertThrows(FileBusinessException.class, () -> service.move(1, 2, 99)).code());
        when(entries.findByIdAndUserId(3, 1)).thenReturn(Optional.of(folder(3, 4, "B")));
        when(entries.findByIdAndUserId(4, 1)).thenReturn(Optional.of(folder(4, 3, "C")));
        assertEquals("INVALID_MOVE", assertThrows(FileBusinessException.class, () -> service.move(1, 2, 3)).code());
    }
    @Test void moveChangesOnlyParentAndSameParentIsNoop() {
        writable();
        when(entries.findByIdAndUserId(2, 1)).thenReturn(Optional.of(folder(2, 0, "A")));
        when(entries.findByIdAndUserId(3, 1)).thenReturn(Optional.of(folder(3, 0, "B")));
        assertEquals("0", service.move(1, 2, 0).parentId());
        verify(entries, never()).flush();
        var result = service.move(1, 2, 3);
        assertEquals("3", result.parentId());
        assertEquals("A", result.name());
        verify(entries).flush();
    }
    @Test void ordinaryFileCannotBeParent() {
        FileEntry file = folder(2, 0, "file");
        ReflectionTestUtils.setField(file, "entryType", FileEntry.EntryType.FILE);
        when(entries.findByIdAndUserId(2, 1)).thenReturn(Optional.of(file));
        assertEquals("NOT_A_FOLDER", assertThrows(FileBusinessException.class, () -> access.requireOwnedFolderOrRoot(1, 2)).code());
    }
}
