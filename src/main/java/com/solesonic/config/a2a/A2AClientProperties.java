package com.solesonic.config.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "solesonic.a2a")
public record A2AClientProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("60") long timeoutSeconds,
        String baseUri
) {}
