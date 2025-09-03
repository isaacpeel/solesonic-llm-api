package com.solesonic.exception;

public class AtlassianTokenException extends RuntimeException {
    private final String errorCode;
    private final boolean retriable;

    public AtlassianTokenException(String message, String errorCode, boolean retriable) {
        super(message);
        this.errorCode = errorCode;
        this.retriable = retriable;
    }

    public AtlassianTokenException(String message, String errorCode, boolean retriable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retriable = retriable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetriable() {
        return retriable;
    }
}