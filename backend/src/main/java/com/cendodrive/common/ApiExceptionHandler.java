package com.cendodrive.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.cendodrive.file.FileBusinessException.class)
    ResponseEntity<ApiError> fileFailure(com.cendodrive.file.FileBusinessException ex) {
        return ResponseEntity.status(ex.status()).body(ApiError.of(ex.code(), ex.getMessage()));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_INPUT", "Invalid parameter type"));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "Invalid input", fields));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed() {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_JSON", "Invalid JSON body"));
    }
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of("UNAUTHORIZED", "Unauthorized"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("NOT_FOUND", "Resource not found"));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError.of("METHOD_NOT_ALLOWED", "Method not allowed"));
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMediaType() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiError.of("UNSUPPORTED_MEDIA_TYPE", "Unsupported media type"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            String constraintName = cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    ? constraint.getConstraintName() : null;
            String sqlMessage = cause instanceof java.sql.SQLIntegrityConstraintViolationException sql
                    ? sql.getMessage() : null;
            if (matchesConstraint(constraintName, sqlMessage, "uk_file_entries_name")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiError.of("NAME_CONFLICT", "Name already exists in this folder"));
            }
            if (matchesConstraint(constraintName, sqlMessage, "uk_file_entries_ingest")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiError.of("IDEMPOTENCY_CONFLICT", "Upload task already registered"));
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "uk_users_username".equalsIgnoreCase(constraint.getConstraintName())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", "Username already exists"));
            }
            if (cause instanceof java.sql.SQLIntegrityConstraintViolationException sql
                    && sql.getMessage() != null
                    && sql.getMessage().toLowerCase(Locale.ROOT).contains("uk_users_username")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", "Username already exists"));
            }
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "Internal server error"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> rateLimit(ResponseStatusException ex) {
        if (ex.getStatusCode().value() != 429) throw ex;
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "900")
                .body(ApiError.of("RATE_LIMITED", "Too many login attempts"));
    }
    private static boolean matchesConstraint(String name, String message, String expected) {
        return expected.equalsIgnoreCase(name)
                || (message != null && message.toLowerCase(Locale.ROOT).contains(expected));
    }
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ApiError> authentication(AuthFailure ex) {
        return ResponseEntity.status(ex.status()).body(ApiError.of("UNAUTHORIZED", ex.getMessage()));
    }
    public static class AuthFailure extends RuntimeException {
        private final HttpStatus status;
        public AuthFailure(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus status() { return status; }
    }
}
