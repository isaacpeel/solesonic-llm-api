package com.solesonic.exception.session;

import com.solesonic.model.session.SessionErrorCode;

import java.util.List;

/**
 * A shared-session request that cannot go ahead. The message reaches the client, so it never carries
 * an exception string, a participant's text or another user's id.
 */
public class SessionException extends RuntimeException {

    private final SessionErrorCode errorCode;
    private final List<String> usernames;

    public SessionException(SessionErrorCode errorCode, String userSafeMessage) {
        this(errorCode, userSafeMessage, null);
    }

    /**
     * @param usernames the usernames the request named that could not be used, for the client to mark;
     *                  null when the error is not about particular usernames
     */
    public SessionException(SessionErrorCode errorCode, String userSafeMessage, List<String> usernames) {
        super(userSafeMessage);
        this.errorCode = errorCode;
        this.usernames = usernames == null ? null : List.copyOf(usernames);
    }

    public List<String> getUsernames() {
        return usernames;
    }

    public SessionErrorCode getErrorCode() {
        return errorCode;
    }
}
