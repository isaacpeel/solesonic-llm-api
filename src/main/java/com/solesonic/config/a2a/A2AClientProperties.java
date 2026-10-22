package com.solesonic.config.a2a;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "solesonic.a2a")
public record A2AClientProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("60") long timeoutSeconds,
        String baseUri,
        Litellm litellm
) {

    public record Litellm(String baseUri, String apiKey) {

        @Override
        public @NonNull String toString() {
            return "Litellm[baseUri=" + baseUri + ", apiKey=<redacted>]";
        }
    }
}
