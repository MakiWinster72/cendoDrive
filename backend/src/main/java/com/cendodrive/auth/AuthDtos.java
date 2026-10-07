package com.cendodrive.auth;

import com.cendodrive.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class AuthDtos {
  private AuthDtos() {
  }

  public record RegisterRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,64}") String username,
      @NotBlank @Size(min = 8, max = 128) String password,
      @Size(max = 64) String nickname) {
  }

  public record LoginRequest(@NotBlank String username, @NotBlank String password) {
  }

  public record UserResponse(
      @Schema(description = "用户 ID，按字符串传输以避免 JavaScript 精度丢失", example = "9007199254740993") String id,
      String username, String nickname,
      @Schema(description = "UTC 时间，ISO-8601 带 Z", example = "2026-09-28T08:24:29Z") OffsetDateTime createdAt,
      String vipLevel, long storageUsed, long storageLimit) {
    public static UserResponse from(User user) {
      return new UserResponse(user.getId().toString(), user.getUsername(), user.getNickname(),
          user.getCreatedAt().atOffset(ZoneOffset.UTC), user.getVipLevel(),
          user.getStorageUsed(), user.getStorageLimit());
    }
  }

  public record LoginResponse(@Schema(description = "随机不透明 Bearer Token") String token,
      @Schema(description = "剩余有效秒数", example = "86400") long expiresInSeconds,
      UserResponse user) {
  }
}
