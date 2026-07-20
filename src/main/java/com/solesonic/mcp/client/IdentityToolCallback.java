package com.solesonic.mcp.client;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import reactor.util.context.Context;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * A wrapper around AsyncMcpToolCallback that captures the security context
 * and makes it available for reactive WebClient filters during MCP tool execution.
 * <p>
 * This class solves the context propagation issue where the user's JWT token
 * is not available in WebClient filters when MCP tools are invoked during
 * streaming chat responses.
 */
public class IdentityToolCallback implements ToolCallback {
    private static final Logger log = LoggerFactory.getLogger(IdentityToolCallback.class);

    public static final String USER_TOKEN = "userToken";
    public static final String SECURITY_CONTEXT_KEY = "SECURITY_CONTEXT";
    private static final ThreadLocal<Context> TOOL_CALL_CONTEXT = new ThreadLocal<>();
    private static final JsonMapper jsonMapper = new JsonMapper();

    private static final TypeReference<List<Map<String, Object>>> CONTENT_LIST_TYPE = new TypeReference<>() {
    };
    public static final String TEXT = "text";

    private final ToolCallback delegate;
    private final ToolMetadata toolMetadata;
    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public IdentityToolCallback(ToolCallback tool, JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter) {

        this.delegate = tool;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;

        boolean returnDirect = tool.getToolMetadata().returnDirect();
        this.toolMetadata = ToolMetadata.builder()
                .returnDirect(returnDirect)
                .build();

        log.debug("Tool definition for {}: {}, returnDirect={}", tool.getToolDefinition().name(), delegate.getToolDefinition(), returnDirect);
    }

    @Override
    @NonNull
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    @NonNull
    public ToolMetadata getToolMetadata() {
        return toolMetadata;
    }

    @Override
    @NonNull
    public String call(@NonNull String toolCallInput) {
        return call(toolCallInput, null);
    }

    @Override
    @NonNull
    public String call(@NonNull String toolCallInput, @Nullable ToolContext toolContext) {
        log.info("Tool callback wrapper invoked for: {}", delegate.getToolDefinition().name());

        if (toolContext == null) {
            log.error("Tool context is required but was null for tool call: {}", delegate.getToolDefinition().name());
            throw new IllegalArgumentException("ToolContext is required but was null for tool call: " + delegate.getToolDefinition().name());
        }

        Map<String, Object> toolContextMap = toolContext.getContext();
        Object userTokenValue = toolContextMap.get(USER_TOKEN);

        if (userTokenValue == null || StringUtils.isBlank(userTokenValue.toString())) {
            log.error("No user token found in context for tool call: {}", delegate.getToolDefinition().name());
            throw new IllegalStateException("No user token provided for tool call: " + delegate.getToolDefinition().name());
        }

        String userToken = userTokenValue.toString();

        log.info("User token added to reactive context.");
        Map<String, Object> filteredContextMap = new HashMap<>(toolContextMap);
        filteredContextMap.remove(USER_TOKEN);

        ToolContext filteredToolContext = new ToolContext(filteredContextMap);

        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(USER_TOKEN, userToken);

        Context reactiveContext = Context.of(SECURITY_CONTEXT_KEY, contextMap);
        TOOL_CALL_CONTEXT.set(reactiveContext);

        Jwt jwt = jwtDecoder.decode(userToken);
        Authentication authentication = jwtAuthenticationConverter.convert(jwt);

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);

        log.info("Security context authentication restored for tool call: {}", delegate.getToolDefinition().name());

        try {
            String rawResult = delegate.call(toolCallInput, filteredToolContext);
            return extractText(rawResult);
        } finally {
            SecurityContextHolder.clearContext();
            TOOL_CALL_CONTEXT.remove();
        }
    }

    private String extractText(String rawResult) {
        if (!toolMetadata.returnDirect() || StringUtils.isBlank(rawResult)) {
            return rawResult;
        }

        List<Map<String, Object>> contentList = jsonMapper.readValue(rawResult, CONTENT_LIST_TYPE);
        return contentList.stream()
                .map(entry -> entry.getOrDefault(TEXT, "").toString())
                .collect(Collectors.joining());
    }

    public static boolean hasContext() {
        return TOOL_CALL_CONTEXT.get() != null;
    }

    /**
     * Gets the reactive context stored in ThreadLocal.
     * This is called by the WebClient filter to access the security context.
     */
    public static Context toolCallContext() {
        Context context = TOOL_CALL_CONTEXT.get();
        return context != null ? context : Context.empty();
    }

}