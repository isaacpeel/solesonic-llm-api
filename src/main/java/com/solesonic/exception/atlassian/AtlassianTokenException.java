package com.solesonic.exception.atlassian;

import org.springframework.http.HttpStatusCode;

public class AtlassianTokenException extends RuntimeException {
    private final HttpStatusCode errorCode;
    private final boolean retriable;

    public AtlassianTokenException(String message, HttpStatusCode errorCode, boolean retriable) {
        super(message);
        this.errorCode = errorCode;
        this.retriable = retriable;
    }

    public AtlassianTokenException(String message, HttpStatusCode errorCode, boolean retriable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retriable = retriable;
    }

    public HttpStatusCode getErrorCode() {
        return errorCode;
    }

    public boolean isRetriable() {
        return retriable;
    }
}