package com.cendodrive.auth;

import com.cendodrive.auth.AuthDtos.*;
import com.cendodrive.common.ApiExceptionHandler.AuthFailure;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    public static final long SESSION_SECONDS = 86400;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    public AuthService(UserRepository users, PasswordEncoder encoder, StringRedisTemplate redis) {
        this.users = users; this.encoder = encoder; this.redis = redis;
    }
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String username = request.username().trim().toLowerCase(java.util.Locale.ROOT);
        String nickname = request.nickname() == null || request.nickname().isBlank() ? username : request.nickname().trim();
        User user = users.saveAndFlush(new User(username, encoder.encode(request.password()), nickname));
        return UserResponse.from(user);
    }
    public LoginResponse login(LoginRequest request) {
        User user = users.findByUsername(request.username().trim().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new AuthFailure(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!user.isActive() || !encoder.matches(request.password(), user.getPasswordHash())) {
            throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), user.getId().toString(), Duration.ofSeconds(SESSION_SECONDS));
        return new LoginResponse(token, SESSION_SECONDS, UserResponse.from(user));
    }
    public void logout(String token) { redis.delete(key(token)); }
    public User authenticate(String token) {
        String id = redis.opsForValue().get(key(token));
        if (id == null) throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Unauthorized");
        try {
            User user = users.findById(Long.parseLong(id)).orElseThrow();
            if (user.isActive()) return user;
        } catch (IllegalArgumentException ignored) { /* invalid session */ }
        throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }
    private static String key(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "session:" + HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
