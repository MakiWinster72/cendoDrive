package com.cendodrive.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
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
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict() {
        // TODO: narrow this mapping to the username unique index; other data failures are not conflicts.
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", "Username already exists"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> rateLimit(ResponseStatusException ex) {
        if (ex.getStatusCode().value() != 429) throw ex;
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "900")
                .body(ApiError.of("RATE_LIMITED", "Too many login attempts"));
    }
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ApiError> authentication(AuthFailure ex) {
        return ResponseEntity.status(ex.status()).body(ApiError.of("UNAUTHORIZED", ex.getMessage()));
    }
    @ExceptionHandler(DriveFailure.class)
    ResponseEntity<ApiError> drive(DriveFailure ex) {
        return ResponseEntity.status(ex.status()).body(ApiError.of(ex.code(), ex.getMessage()));
    }
    public static class AuthFailure extends RuntimeException {
        private final HttpStatus status;
        public AuthFailure(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus status() { return status; }
    }
    public static class DriveFailure extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        public DriveFailure(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
