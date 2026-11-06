package com.solesonic.exception.push;

import com.solesonic.model.push.PushErrorCode;

/**
 * A push request that cannot be served. The message reaches the client, so it never carries an
 * endpoint, a key or an exception string.
 */
public class PushException extends RuntimeException {

    private final PushErrorCode errorCode;

    public PushException(PushErrorCode errorCode, String userSafeMessage) {
        super(userSafeMessage);
        this.errorCode = errorCode;
    }

    public static PushException disabled() {
        return new PushException(PushErrorCode.PUSH_DISABLED, "Push notifications are disabled.");
    }

    public static PushException notFound() {
        return new PushException(PushErrorCode.NOT_FOUND, "Not found.");
    }

    public PushErrorCode getErrorCode() {
        return errorCode;
    }
}
