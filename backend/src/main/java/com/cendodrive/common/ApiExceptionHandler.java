package com.cendodrive.common;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid input"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Username already exists"));
    }
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<Map<String, String>> authentication(AuthFailure ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("error", ex.getMessage()));
    }
    public static class AuthFailure extends RuntimeException {
        private final HttpStatus status;
        public AuthFailure(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus status() { return status; }
    }
}
