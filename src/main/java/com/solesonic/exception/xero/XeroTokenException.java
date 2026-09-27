package com.solesonic.exception.xero;

import org.springframework.http.HttpStatusCode;

/**
 * A failure obtaining or renewing a Xero OAuth2 token.
 * <p>
 * {@code retriable} is what separates "Xero had a bad moment" from "this grant is gone". Xero
 * answers a dead grant with {@code invalid_grant}, and no amount of retrying revives it — only
 * re-consent does — so that case must never be reported as retriable.
 */
public class XeroTokenException extends RuntimeException {
    private final HttpStatusCode errorCode;
    private final boolean retriable;

    public XeroTokenException(String message, HttpStatusCode errorCode, boolean retriable) {
        super(message);
        this.errorCode = errorCode;
        this.retriable = retriable;
    }

    public XeroTokenException(String message, HttpStatusCode errorCode, boolean retriable, Throwable cause) {
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
