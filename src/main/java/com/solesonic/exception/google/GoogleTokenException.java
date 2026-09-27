package com.solesonic.exception.google;

import org.springframework.http.HttpStatusCode;

public class GoogleTokenException extends RuntimeException {
    private final HttpStatusCode errorCode;
    private final boolean retriable;

    public GoogleTokenException(String message, HttpStatusCode errorCode, boolean retriable) {
        super(message);
        this.errorCode = errorCode;
        this.retriable = retriable;
    }

    public GoogleTokenException(String message, HttpStatusCode errorCode, boolean retriable, Throwable cause) {
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
