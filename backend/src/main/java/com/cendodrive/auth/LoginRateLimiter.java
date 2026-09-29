package com.cendodrive.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LoginRateLimiter {
    private static final int MAX_FAILURES = 5;
    private static final int WINDOW_SECONDS = 900;
    private static final DefaultRedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return n",
            Long.class);
    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public void check(String username) {
        String value = redis.opsForValue().get(key(username));
        if (value != null && Long.parseLong(value) >= MAX_FAILURES) reject();
    }

    public void failure(String username) {
        Long count = redis.execute(RECORD_FAILURE, List.of(key(username)), Integer.toString(WINDOW_SECONDS));
        if (count != null && count >= MAX_FAILURES) reject();
    }

    public void success(String username) { redis.delete(key(username)); }

    private static String key(String username) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(username.getBytes(StandardCharsets.UTF_8));
            return "login:fail:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void reject() {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts");
    }
}
