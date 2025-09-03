package com.solesonic.exception;

public class RefreshTokenConflictException extends RuntimeException {
    public RefreshTokenConflictException(String message) {
        super(message);
    }
}
