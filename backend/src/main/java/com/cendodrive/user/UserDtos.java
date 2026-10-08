package com.cendodrive.user;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public final class UserDtos {
  private UserDtos() {}
  public record ProfileResponse(String id, String username, String nickname, boolean hasAvatar) {}
  public record ProfileRequest(@NotBlank @Size(max=64) String nickname) {}
  public record PasswordRequest(@NotBlank @Size(max=128) String currentPassword,
      @NotBlank @Size(min=8,max=72) String newPassword) {}
  public record DeleteRequest(@NotBlank @Size(max=128) String password,
      @NotBlank @Pattern(regexp="注销账号") String confirmation) {}
  public record DeletionResponse(OffsetDateTime deletedAt, OffsetDateTime purgeAfter) {}
}
