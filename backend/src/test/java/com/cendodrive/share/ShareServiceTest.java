package com.cendodrive.share;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveFile;
import com.cendodrive.drive.DriveService;
import com.cendodrive.share.ShareDtos.*;
import com.cendodrive.user.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareServiceTest {
  @Mock ShareLinkRepository links;
  @Mock ShareAccessStore access;
  @Mock DriveService drive;
  @Mock UserRepository users;
  @Mock User owner;
  @Mock User recipient;
  ShareService service;
  static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  static final String TOKEN = "a".repeat(32);

  @BeforeEach void setup() {
    service = new ShareService(links, access, drive, users, Clock.fixed(NOW, ZoneOffset.UTC));
    lenient().when(owner.getId()).thenReturn(7L);
  }

  ShareLink link(long seconds) {
    ShareLink link = ShareLink.create(7L, 42L, TOKEN, "hello.txt", 5, NOW, seconds);
    ReflectionTestUtils.setField(link, "id", 51L);
    return link;
  }

  void resolve(ShareLink link) {
    when(links.findByToken(TOKEN)).thenReturn(Optional.of(link));
  }

  DriveFile source() {
    DriveFile file = DriveFile.uploaded(7L, 99L, "hello.txt", 5, "original");
    ReflectionTestUtils.setField(file, "id", 42L);
    ReflectionTestUtils.setField(file, "updatedAt", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    when(drive.shareableFile(owner, 42L)).thenReturn(file);
    return file;
  }

  @Test void createsOpaqueTokenWithOwnerAndBoundedExpiry() {
    DriveFile file = DriveFile.uploaded(7L, null, "hello.txt", 5, "original");
    ReflectionTestUtils.setField(file, "id", 42L);
    when(drive.shareableFile(owner, 42L)).thenReturn(file);
    when(links.saveAndFlush(any())).thenAnswer(call -> {
      ShareLink value = call.getArgument(0);
      ReflectionTestUtils.setField(value, "id", 51L);
      assertEquals(7L, value.getOwnerId());
      assertEquals(NOW.plusSeconds(60), value.getExpiresAt());
      return value;
    });
    ShareResponse response = service.create(owner, new CreateShareRequest(42L, 60L));
    assertTrue(response.token().matches("[a-f0-9]{32}"));
    verify(access).enable(any(), eq(NOW));
  }

  @Test void cannotCreateFromAnotherOwnersFile() {
    when(drive.shareableFile(owner, 42L)).thenThrow(new DriveFailure(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "missing"));
    assertThrows(DriveFailure.class, () -> service.create(owner, new CreateShareRequest(42L, 60L)));
    verifyNoInteractions(links, access);
  }

  @Test void refusesInvalidExpiriesWithoutStorageAccess() {
    for (Long seconds : Arrays.asList(null, 0L, -1L, 2592001L))
      assertThrows(DriveFailure.class, () -> service.create(owner, new CreateShareRequest(42L, seconds)));
    verifyNoInteractions(drive, links, access);
  }

  @Test void listsOnlyAuthenticatedOwnersRecords() {
    when(links.findAllByOwnerIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(link(60)));
    assertEquals(1, service.list(owner).size());
    verify(links).findAllByOwnerIdOrderByCreatedAtDesc(7L);
  }

  @Test void otherUserCannotCancelOrRevoke() {
    when(links.findByIdAndOwnerId(51L, 7L)).thenReturn(Optional.empty());
    assertEquals("SHARE_NOT_FOUND", assertThrows(DriveFailure.class, () -> service.cancel(owner, 51L)).code());
    verifyNoInteractions(access);
  }

  @Test void cancelImmediatelyRevokesRedisAndPersistsStatus() {
    ShareLink link = link(60);
    when(links.findByIdAndOwnerId(51L, 7L)).thenReturn(Optional.of(link));
    service.cancel(owner, 51L);
    verify(access).revoke(link);
    assertTrue(link.isCancelled());
    verify(links).saveAndFlush(link);
  }

  @Test void expiryAndCancellationFailEvenWithStaleRedisCredentials() {
    ShareLink expired = link(0);
    resolve(expired);
    assertUnavailable();
    ShareLink cancelled = link(60);
    cancelled.cancel();
    resolve(cancelled);
    assertUnavailable();
    verifyNoInteractions(access, drive, users);
  }

  @Test void missingRedisCredentialFailsClosed() {
    resolve(link(60));
    assertUnavailable();
    verifyNoInteractions(drive, users);
  }

  @Test void missingOrMalformedTokensDoNotReachFileStorage() {
    for (String token : List.of("", "bad", "../file", "a".repeat(33)))
      assertEquals("SHARE_NOT_FOUND", assertThrows(DriveFailure.class, () -> service.get(token)).code());
    when(links.findByToken(TOKEN)).thenReturn(Optional.empty());
    assertUnavailable();
    verifyNoInteractions(access, drive, users);
  }

  @Test void publicMetadataHidesOwnersPrivateFolder() {
    ShareLink link = link(60);
    resolve(link);
    when(access.allows(link)).thenReturn(true);
    source();
    assertNull(service.get(TOKEN).file().parentId());
    assertEquals("hello.txt", service.get(TOKEN).file().name());
  }

  @Test void deletedSourceIsUnavailableDespiteLiveToken() {
    ShareLink link = link(60);
    resolve(link);
    when(access.allows(link)).thenReturn(true);
    when(users.findById(7L)).thenReturn(Optional.of(owner));
    when(drive.shareableFile(owner, 42L)).thenThrow(new DriveFailure(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "missing"));
    assertUnavailable();
  }

  @Test void downloadsAsOwnerButSavesAsRecipient() throws Exception {
    ShareLink link = link(60);
    resolve(link);
    when(access.allows(link)).thenReturn(true);
    source();
    service.download(TOKEN);
    verify(drive).download(owner, 42L);
    service.save(recipient, TOKEN, new SaveShareRequest(12L));
    verify(drive).saveSharedFile(recipient, owner, 42L, 12L);
  }

  void assertUnavailable() {
    assertEquals("SHARE_NOT_FOUND", assertThrows(DriveFailure.class, () -> service.get(TOKEN)).code());
  }
}
