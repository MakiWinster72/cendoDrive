package com.cendodrive.auth;

import com.cendodrive.auth.AuthDtos.*;
import com.cendodrive.common.ApiExceptionHandler.AuthFailure;
import com.cendodrive.user.User;
import com.cendodrive.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserRepository users;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    AuthService service;
    @BeforeEach void setup() { service = new AuthService(users, new BCryptPasswordEncoder(), redis); }

    @Test void registrationHashesPasswordAndNormalizesName() {
        when(users.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
        service.register(new RegisterRequest("MaKi", "password123", null));
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captor.capture());
        assertEquals("maki", captor.getValue().getUsername());
        assertNotEquals("password123", captor.getValue().getPasswordHash());
    }
    @Test void loginAndLogoutUseHashedSessionKey() {
        User user = mock(User.class);
        when(user.isActive()).thenReturn(true);
        when(user.getPasswordHash()).thenReturn(new BCryptPasswordEncoder().encode("password123"));
        when(user.getId()).thenReturn(42L);
        when(users.findByUsername("maki")).thenReturn(Optional.of(user));
        when(redis.opsForValue()).thenReturn(values);
        var result = service.login(new LoginRequest("Maki", "password123"));
        assertEquals(86400, result.expiresInSeconds());
        verify(values).set(startsWith("session:"), eq("42"), eq(java.time.Duration.ofDays(1)));
        service.logout(result.token());
        verify(redis).delete(startsWith("session:"));
    }
    @Test void invalidCredentialsFail() {
        when(users.findByUsername("maki")).thenReturn(Optional.empty());
        assertThrows(AuthFailure.class, () -> service.login(new LoginRequest("maki", "bad")));
    }
    @Test void expiredSessionFails() {
        when(redis.opsForValue()).thenReturn(values);
        assertThrows(AuthFailure.class, () -> service.authenticate("expired"));
    }
}
