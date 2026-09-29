package com.cendodrive.file;

import org.springframework.http.HttpStatus;

public class FileBusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public FileBusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static FileBusinessException invalid(String code, String message) {
        return new FileBusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
    public static FileBusinessException notFound() {
        return new FileBusinessException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File or folder not found");
    }
    public static FileBusinessException conflict(String code, String message) {
        return new FileBusinessException(HttpStatus.CONFLICT, code, message);
    }
}
