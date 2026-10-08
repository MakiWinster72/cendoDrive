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
  private final LoginRateLimiter limiter;
  private final SecureRandom random = new SecureRandom();
  private final java.time.Clock clock;

  public AuthService(UserRepository users, PasswordEncoder encoder, StringRedisTemplate redis,
      LoginRateLimiter limiter) {
    this(users,encoder,redis,limiter,java.time.Clock.systemUTC());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AuthService(UserRepository users, PasswordEncoder encoder, StringRedisTemplate redis,
      LoginRateLimiter limiter, java.time.Clock clock) {
    this.users = users;
    this.encoder = encoder;
    this.redis = redis;
    this.limiter = limiter;
    this.clock = clock;
  }

  @Transactional
  public UserResponse register(RegisterRequest request) {
    String username = request.username().trim().toLowerCase(java.util.Locale.ROOT);
    String nickname = request.nickname() == null || request.nickname().isBlank() ? username : request.nickname().trim();
    User user = users.saveAndFlush(new User(username, encoder.encode(request.password()), nickname));
    return UserResponse.from(user);
  }

  public LoginResponse login(LoginRequest request) {
    String username = request.username().trim().toLowerCase(java.util.Locale.ROOT);
    limiter.check(username);
    User user = users.findByUsername(username).orElse(null);
    if (user == null || !user.isActive() || !encoder.matches(request.password(), user.getPasswordHash())) {
      limiter.failure(username);
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
    limiter.success(username);
    return newSession(user);
  }

  @Transactional
  public void restore(LoginRequest request) {
    String username=request.username().trim().toLowerCase(java.util.Locale.ROOT);
    limiter.check(username);
    User candidate=users.findByUsername(username).orElse(null);
    User user=candidate==null ? null : users.lock(candidate.getId()).orElse(null);
    if (user==null || user.isActive() || user.getDeletedAt()==null
        || !java.time.LocalDateTime.now(clock).isBefore(user.getDeletedAt().plusDays(com.cendodrive.user.UserService.DELETION_DAYS))
        || !encoder.matches(request.password(),user.getPasswordHash())) {
      limiter.failure(username);
      throw new AuthFailure(HttpStatus.UNAUTHORIZED,"Invalid credentials or recovery period expired");
    }
    limiter.success(username);
    user.restoreAccount();
  }

  private LoginResponse newSession(User user) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    redis.opsForValue().set(key(token), user.getId() + ":" + user.getAuthVersion(), Duration.ofSeconds(SESSION_SECONDS));
    return new LoginResponse(token, SESSION_SECONDS, UserResponse.from(user));
  }

  public void logout(String token) {
    redis.delete(key(token));
  }

  public User authenticate(String token) {
    String session = redis.opsForValue().get(key(token));
    if (session == null)
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "Unauthorized");
    try {
      String[] parts=session.split(":",-1);
      if (parts.length>2) throw new IllegalArgumentException("Invalid session");
      long version=parts.length==1 ? 0 : Long.parseLong(parts[1]);
      User user = users.findById(Long.parseLong(parts[0])).orElseThrow();
      if (user.isActive() && user.getAuthVersion()==version)
        return user;
    } catch (IllegalArgumentException | java.util.NoSuchElementException ignored) {
      /* invalid session */ }
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
