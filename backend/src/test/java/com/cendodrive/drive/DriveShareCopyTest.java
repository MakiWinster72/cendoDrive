package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.storage.FileStorage;
import com.cendodrive.storage.StorageCleanupService;
import com.cendodrive.index.AiIndexTaskService;
import com.cendodrive.user.User;
import java.io.*;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DriveShareCopyTest {
  @Mock DriveFileRepository files;
  @Mock FileStorage storage;
  @Mock User owner;
  @Mock User recipient;
  @Mock FileQuotaService quota;
  @Mock AiIndexTaskService indexTasks;
  @Mock StorageCleanupService storageCleanup;
  DriveService drive;
  DriveFile original;

  @BeforeEach void setup() {
    drive = new DriveService(files, storage, "/tmp/cendo-share-copy-test", quota, indexTasks, storageCleanup);
    lenient().when(quota.check(eq(recipient), anyLong(), isNull())).thenReturn(recipient);
    lenient().when(owner.getId()).thenReturn(7L);
    lenient().when(recipient.getId()).thenReturn(8L);
    original = DriveFile.uploaded(7L, null, "hello.txt", 5, "original");
    ReflectionTestUtils.setField(original, "id", 42L);
    when(files.findByIdAndOwnerId(42L, 7L)).thenReturn(Optional.of(original));
  }

  void content() throws IOException {
    doAnswer(call -> {
      OutputStream output = call.getArgument(1);
      output.write("hello".getBytes());
      return null;
    }).when(storage).download(eq("original"), any());
    when(storage.upload(any(), eq(5L), eq("txt"))).thenAnswer(call -> {
      InputStream input = call.getArgument(0);
      assertEquals("hello", new String(input.readAllBytes()));
      return "independent-copy";
    });
  }

  @Test void copiesBytesAndRegistersUnderRecipientWithNewStorageKey() throws Exception {
    content();
    when(files.saveAndFlush(any())).thenAnswer(call -> {
      DriveFile file = call.getArgument(0);
      assertEquals(8L, file.getOwnerId());
      assertEquals("independent-copy", file.getStorageKey());
      assertNotEquals(original.getStorageKey(), file.getStorageKey());
      assertNull(file.getParentId());
      ReflectionTestUtils.setField(file, "id", 100L);
      ReflectionTestUtils.setField(file, "updatedAt", LocalDateTime.now());
      return file;
    });
    assertEquals("100", drive.saveSharedFile(recipient, owner, 42L, null).id());
    verify(storage, never()).delete("original");
    verify(quota, times(2)).check(recipient, 5L, null);
    verify(quota).refresh(recipient);
  }

  @Test void rejectsQuotaBeforeReadingOrWritingStorage() {
    when(quota.check(recipient, 5L, null)).thenThrow(new DriveFailure(
        org.springframework.http.HttpStatus.INSUFFICIENT_STORAGE, "QUOTA_EXCEEDED", "Storage quota exceeded"));
    assertEquals("QUOTA_EXCEEDED", assertThrows(DriveFailure.class,
        () -> drive.saveSharedFile(recipient, owner, 42L, null)).code());
    verifyNoInteractions(storage);
    verify(files, never()).saveAndFlush(any());
    verify(quota, never()).refresh(any());
  }

  @Test void rejectsForeignDestinationFolderBeforeDownloading() {
    when(files.findByIdAndOwnerId(99L, 8L)).thenReturn(Optional.empty());
    assertThrows(DriveFailure.class, () -> drive.saveSharedFile(recipient, owner, 42L, 99L));
    verifyNoInteractions(storage);
  }

  @Test void rejectsNameConflictWithoutCopying() {
    when(files.existsByOwnerIdAndParentIdIsNullAndDeletedAtIsNullAndNameIgnoreCase(8L, "hello.txt")).thenReturn(true);
    assertEquals("NAME_CONFLICT", assertThrows(DriveFailure.class,
        () -> drive.saveSharedFile(recipient, owner, 42L, null)).code());
    verifyNoInteractions(storage);
  }

  @Test void cleansIndependentBlobWhenDatabaseRegistrationFails() throws Exception {
    content();
    when(files.saveAndFlush(any())).thenThrow(new IllegalStateException("database failed"));
    assertThrows(IllegalStateException.class, () -> drive.saveSharedFile(recipient, owner, 42L, null));
    verify(storage).delete("independent-copy");
    verify(storage, never()).delete("original");
  }

  @Test void rejectsTrashedAncestorAndNeverCopiesHiddenChild() {
    ReflectionTestUtils.setField(original, "parentId", 9L);
    DriveFile folder = DriveFile.folder(7L, null, "parent");
    folder.moveToTrash();
    when(files.findByIdAndOwnerId(9L, 7L)).thenReturn(Optional.of(folder));
    assertThrows(DriveFailure.class, () -> drive.saveSharedFile(recipient, owner, 42L, null));
    verifyNoInteractions(storage);
  }
}
