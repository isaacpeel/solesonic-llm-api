package com.solesonic.model.llm;

import org.jspecify.annotations.NonNull;

/**
 * The body of {@code PUT /llm-keys/{userId}}. {@link #toString()} is redacted for the same reason as
 * {@link LlmApiKey}'s.
 */
public record UserLlmKeyRequest(String apiKey) {

    @Override
    public @NonNull String toString() {
        return "UserLlmKeyRequest[apiKey=redacted]";
    }
}
