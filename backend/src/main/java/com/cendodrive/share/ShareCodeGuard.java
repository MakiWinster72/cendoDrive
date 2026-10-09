package com.cendodrive.share;

import com.cendodrive.auth.LoginRateLimiter;
import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Recipient verification uses BCrypt; codes are returned only in owner management responses. */
@Component
public class ShareCodeGuard {
  private final PasswordEncoder passwords;
  private final LoginRateLimiter limiter;

  public ShareCodeGuard(PasswordEncoder passwords, LoginRateLimiter limiter) {
    this.passwords = passwords;
    this.limiter = limiter;
  }

  String hash(String code) {
    if (code == null) return null;
    if (!code.matches("[A-Za-z0-9]{4,16}"))
      throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Code requires 4-16 letters or digits");
    return passwords.encode(code);
  }

  void verify(ShareLink link, String code, String client) {
    if (!link.hasExtractionCode()) return;
    if (code == null || code.isEmpty())
      throw new DriveFailure(HttpStatus.FORBIDDEN, "SHARE_CODE_REQUIRED", "Extraction code required");
    // Domain separation prevents collisions with account-password limits. Do not trust X-Forwarded-For.
    String key = "share-code:" + link.getToken() + ":" + client;
    limiter.check(key);
    if (!code.matches("[A-Za-z0-9]{4,16}") || !passwords.matches(code, link.getExtractionCodeHash())) {
      limiter.failure(key);
      throw new DriveFailure(HttpStatus.FORBIDDEN, "SHARE_CODE_INVALID", "Invalid extraction code");
    }
    limiter.success(key);
  }
}
