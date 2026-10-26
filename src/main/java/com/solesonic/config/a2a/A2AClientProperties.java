package com.solesonic.config.a2a;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "solesonic.a2a")
public record A2AClientProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("60") long timeoutSeconds,
        String baseUri,
        Litellm litellm,
        Sound sound
) {

    /**
     * @param maxBytes the largest decoded clip an agent may send; a larger one is ignored
     * @param ttl      how long a clip is kept after it was last cued
     */
    public record Sound(DataSize maxBytes, Duration ttl) {
    }

    public record Litellm(String baseUri, String apiKey) {

        @Override
        public @NonNull String toString() {
            return "Litellm[baseUri=" + baseUri + ", apiKey=<redacted>]";
        }
    }
}
