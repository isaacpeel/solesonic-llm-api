package com.solesonic.exception.chat;

import com.solesonic.model.chat.model.ChatModelErrorCode;

/**
 * A model that could not be chosen. The message reaches the client, so it never carries an
 * exception string or the endpoint's address.
 */
public class ChatModelException extends RuntimeException {

    private final ChatModelErrorCode errorCode;

    public ChatModelException(ChatModelErrorCode errorCode, String userSafeMessage) {
        super(userSafeMessage);
        this.errorCode = errorCode;
    }

    public ChatModelErrorCode getErrorCode() {
        return errorCode;
    }
}
