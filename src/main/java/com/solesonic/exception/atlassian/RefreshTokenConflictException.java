package com.solesonic.exception.atlassian;

public class RefreshTokenConflictException extends RuntimeException {
    public RefreshTokenConflictException(String message) {
        super(message);
    }
}
