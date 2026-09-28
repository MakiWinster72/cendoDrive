package com.cendodrive.auth;

import com.cendodrive.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserResponseTest {
    @Test void idIsStringAndUtcTimestampHasZ() throws Exception {
        User user = mock(User.class);
        when(user.getId()).thenReturn(9007199254740993L);
        when(user.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 9, 28, 8, 24, 29));
        var response = AuthDtos.UserResponse.from(user);
        var json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(response);
        assertTrue(json.contains("\"id\":\"9007199254740993\""));
        assertTrue(json.contains("\"createdAt\":\"2026-09-28T08:24:29Z\""));
    }
}
