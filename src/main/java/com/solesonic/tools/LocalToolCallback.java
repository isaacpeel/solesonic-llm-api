package com.solesonic.tools;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.HashMap;
import java.util.Map;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;

/**
 * Runs an in-process tool as the user whose turn it is.
 * <p>
 * Unlike {@code IdentityToolCallback}, nothing here leaves the process, so the tool context passes
 * through whole — the user, chat and reference attachment ids a local tool works from — and only the
 * raw token is kept back. The user's identity is restored for the call so a tool gated by
 * {@code @PreAuthorize} is checked against them, and cleared afterwards.
 */
public class LocalToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public LocalToolCallback(ToolCallback delegate,
                             JwtDecoder jwtDecoder,
                             JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.delegate = delegate;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Override
    @NonNull
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    @NonNull
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    @NonNull
    public String call(@NonNull String toolInput) {
        return call(toolInput, null);
    }

    @Override
    @NonNull
    public String call(@NonNull String toolInput, @Nullable ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();

        if (toolContext == null) {
            throw new IllegalArgumentException("ToolContext is required but was null for tool call: " + toolName);
        }

        Map<String, Object> toolContextMap = toolContext.getContext();

        if (!(toolContextMap.get(USER_TOKEN) instanceof String userToken) || StringUtils.isBlank(userToken)) {
            throw new IllegalStateException("No user token provided for tool call: " + toolName);
        }

        Map<String, Object> passedContext = new HashMap<>(toolContextMap);
        passedContext.remove(USER_TOKEN);

        SecurityContext previousContext = SecurityContextHolder.getContext();

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(jwtAuthenticationConverter.convert(jwtDecoder.decode(userToken)));
        SecurityContextHolder.setContext(securityContext);

        try {
            return delegate.call(toolInput, new ToolContext(passedContext));
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }
}
