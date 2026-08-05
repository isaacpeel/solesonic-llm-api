package com.solesonic.mcp.client;

import com.solesonic.service.image.GeneratedImageToolInterceptor;
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
import java.util.function.Supplier;
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

    /**
     * The generating user, needed by result post-processing that persists something on their
     * behalf. Stripped before the call like {@link #USER_TOKEN}: an MCP server has no business
     * knowing our internal user ids.
     */
    public static final String USER_ID = "userId";

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
    private final GeneratedImageToolInterceptor generatedImageToolInterceptor;

    public IdentityToolCallback(ToolCallback tool,
                                JwtDecoder jwtDecoder,
                                JwtAuthenticationConverter jwtAuthenticationConverter,
                                GeneratedImageToolInterceptor generatedImageToolInterceptor) {

        this.delegate = tool;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.generatedImageToolInterceptor = generatedImageToolInterceptor;

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
        filteredContextMap.remove(USER_ID);

        ToolContext filteredToolContext = new ToolContext(filteredContextMap);

        Jwt jwt = jwtDecoder.decode(userToken);
        Authentication authentication = jwtAuthenticationConverter.convert(jwt);

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);

        log.info("Security context authentication restored for tool call: {}", delegate.getToolDefinition().name());

        try {
            return withUserToken(userToken, () -> postProcess(
                    delegate.call(toolCallInput, filteredToolContext), toolCallInput, toolContextMap));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Everything that happens to a tool result between the MCP server and the model.
     * <p>
     * Order matters: image interception runs first and is the only thing standing between the
     * model's context window and a couple of megabytes of base64. It replaces the result outright,
     * so nothing downstream ever sees the image data.
     */
    private String postProcess(String rawResult, String toolCallInput, Map<String, Object> toolContextMap) {
        String toolName = delegate.getToolDefinition().name();

        if (generatedImageToolInterceptor.handles(toolName)) {
            return generatedImageToolInterceptor.intercept(toolCallInput, rawResult, toolContextMap);
        }

        return extractText(rawResult);
    }

    /**
     * Runs {@code action} with {@code userToken} visible to the MCP {@code WebClient} filter, so the
     * call it makes travels on the user's own identity — exchanged for an on-behalf-of token —
     * rather than on the application's client credentials.
     * <p>
     * This exists for the callers that talk to the MCP server directly instead of through a model's
     * tool-calling loop; without it the filter finds no identity and falls back to client
     * credentials, which would flatten every per-user authorization the MCP server enforces into a
     * single service account. The token lives in a {@link ThreadLocal} because the filter reads it
     * on the subscribing thread, which is the thread that made the blocking call.
     * <p>
     * Any token already bound is restored on exit, so nesting is safe.
     */
    public static <T> T withUserToken(String userToken, Supplier<T> action) {
        Context previousContext = TOOL_CALL_CONTEXT.get();

        TOOL_CALL_CONTEXT.set(Context.of(SECURITY_CONTEXT_KEY, Map.of(USER_TOKEN, userToken)));

        try {
            return action.get();
        } finally {
            if (previousContext == null) {
                TOOL_CALL_CONTEXT.remove();
            } else {
                TOOL_CALL_CONTEXT.set(previousContext);
            }
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