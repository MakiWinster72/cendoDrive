package com.cendodrive.auth;

import com.cendodrive.user.User;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

public final class AuthDtos {
    private AuthDtos() {}
    public record RegisterRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,64}") String username,
                                  @NotBlank @Size(min = 8, max = 128) String password,
                                  @Size(max = 64) String nickname) {}
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record UserResponse(Long id, String username, String nickname, LocalDateTime createdAt,
                               String vipLevel, long storageUsed, long storageLimit) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getNickname(), user.getCreatedAt(),
                    user.getVipLevel(), user.getStorageUsed(), user.getStorageLimit());
        }
    }
    public record LoginResponse(String token, long expiresInSeconds, UserResponse user) {}
}
