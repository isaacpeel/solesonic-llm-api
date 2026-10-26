package com.solesonic.exception.llm;

import com.solesonic.model.llm.LlmKeyErrorCode;

/**
 * A key an administrator tried to register that could not be accepted. The message reaches the
 * client, so it never carries the key or the model server's own error text.
 */
public class LlmKeyRejectedException extends RuntimeException {

    private final LlmKeyErrorCode errorCode;

    public LlmKeyRejectedException(LlmKeyErrorCode errorCode, String userSafeMessage) {
        super(userSafeMessage);
        this.errorCode = errorCode;
    }

    public LlmKeyErrorCode getErrorCode() {
        return errorCode;
    }
}
