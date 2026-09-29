package com.cendodrive.common;

import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test void usernameUniqueConstraintReturnsConflict() {
        var sql = new SQLIntegrityConstraintViolationException("Duplicate entry for key 'users.uk_users_username'");
        var result = handler.conflict(new DataIntegrityViolationException("insert failed", sql));
        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("CONFLICT", result.getBody().code());
        assertEquals("Username already exists", result.getBody().message());
    }

    @Test void unrelatedConstraintDoesNotPretendUsernameConflict() {
        var sql = new SQLIntegrityConstraintViolationException("Duplicate entry for key 'other_index'");
        var result = handler.conflict(new DataIntegrityViolationException("insert failed", sql));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertEquals("INTERNAL_ERROR", result.getBody().code());
        assertEquals("Internal server error", result.getBody().message());
    }
}
