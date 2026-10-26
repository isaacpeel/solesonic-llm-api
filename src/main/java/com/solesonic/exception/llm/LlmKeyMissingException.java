package com.solesonic.exception.llm;

/**
 * The user has no model-server key, so nothing may be sent to a model on their behalf. The message is
 * fixed and safe to show the user.
 */
public class LlmKeyMissingException extends RuntimeException {
    public static final String MESSAGE = "Model access isn't configured for your account. Ask an administrator.";

    public LlmKeyMissingException() {
        super(MESSAGE);
    }
}
