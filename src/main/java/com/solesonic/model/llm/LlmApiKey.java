package com.solesonic.model.llm;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;

import java.util.Map;

/**
 * One user's model-server API key. {@link #toString()} never prints it, so a key that reaches a log
 * statement by mistake leaks nothing.
 */
public record LlmApiKey(String value) {
    private static final int HINT_CHARACTERS = 4;

    public LlmApiKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An API key cannot be blank");
        }
    }

    public String bearer() {
        return "Bearer " + value;
    }

    /**
     * The header that replaces the client's own {@code Authorization} on a single request: the OpenAI
     * SDK applies a request's headers after the client's, so this one wins.
     */
    public Map<String, String> authorizationHeaders() {
        return Map.of(HttpHeaders.AUTHORIZATION, bearer());
    }

    public String hint() {
        return value.substring(Math.max(0, value.length() - HINT_CHARACTERS));
    }

    @Override
    public @NonNull String toString() {
        return "LlmApiKey[redacted]";
    }
}
