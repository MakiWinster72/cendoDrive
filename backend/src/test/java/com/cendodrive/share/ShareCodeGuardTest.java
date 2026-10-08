package com.cendodrive.share;

import com.cendodrive.auth.LoginRateLimiter;
import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShareCodeGuardTest {
  final LoginRateLimiter limiter = mock(LoginRateLimiter.class);
  final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  final ShareCodeGuard guard = new ShareCodeGuard(encoder, limiter);
  ShareLink protectedLink() {
    ShareLink link = ShareLink.create(1L, 2L, "a".repeat(32), "file.txt", 5, Instant.now(), 60);
    link.protect(guard.hash("A1b2")); return link;
  }
  @Test void optionalCodesAreBoundedSaltedAndNotPlaintext() {
    assertNull(guard.hash(null));
    String hash = guard.hash("A1b2"); assertNotEquals("A1b2", hash); assertTrue(encoder.matches("A1b2", hash));
    assertNotEquals(hash, guard.hash("A1b2"));
    for (String code : new String[]{"", "abc", "a".repeat(17), "提取密码", "a/bc", "abcd "})
      assertEquals("INVALID_INPUT", assertThrows(DriveFailure.class, () -> guard.hash(code)).code());
  }
  @Test void unprotectedAndMissingCodeDoNotConsumeGuesses() {
    ShareLink link = protectedLink(); link.protect(null); guard.verify(link, null, "ip");
    assertEquals("SHARE_CODE_REQUIRED", assertThrows(DriveFailure.class, () -> guard.verify(protectedLink(), null, "ip")).code());
    verifyNoInteractions(limiter);
  }
  @Test void wrongGuessesAreScopedAndSuccessClearsOnlyThatScope() {
    ShareLink link = protectedLink(); String key = "share-code:" + link.getToken() + ":127.0.0.1";
    assertEquals("SHARE_CODE_INVALID", assertThrows(DriveFailure.class, () -> guard.verify(link, "bad1", "127.0.0.1")).code());
    verify(limiter).failure(key); guard.verify(link, "A1b2", "127.0.0.1"); verify(limiter).success(key);
    doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)).when(limiter).check(key);
    assertThrows(ResponseStatusException.class, () -> guard.verify(link, "A1b2", "127.0.0.1"));
  }
}
