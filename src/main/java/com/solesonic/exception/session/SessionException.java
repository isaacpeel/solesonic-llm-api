package com.solesonic.exception.session;

import com.solesonic.model.session.SessionErrorCode;

/**
 * A shared-session request that cannot go ahead. The message reaches the client, so it never carries
 * an exception string, a participant's text or another user's id.
 */
public class SessionException extends RuntimeException {

    private final SessionErrorCode errorCode;

    public SessionException(SessionErrorCode errorCode, String userSafeMessage) {
        super(userSafeMessage);
        this.errorCode = errorCode;
    }

    public SessionErrorCode getErrorCode() {
        return errorCode;
    }
}
