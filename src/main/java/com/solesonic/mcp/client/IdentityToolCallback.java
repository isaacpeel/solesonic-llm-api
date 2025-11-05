package com.solesonic.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import reactor.util.context.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * A wrapper around SyncMcpToolCallback that captures the security context
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

    private final SyncMcpToolCallback delegate;

    public IdentityToolCallback(McpSyncClient mcpSyncClient, Tool tool) {
        this.delegate = SyncMcpToolCallback.builder()
                .mcpClient(mcpSyncClient)
                .tool(tool)
                .build();
    }

    @Override
    @NonNull
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    @NonNull
    public String call(@NonNull String toolCallInput) {
        return call(toolCallInput, null);
    }

    @Override
    @NonNull
    public String call(@NonNull String toolCallInput, @Nullable ToolContext toolContext) {
        // Capture the current security context
        log.info("Tool callback wrapper invoked for: {}", delegate.getOriginalToolName());
        assert toolContext != null;
        Map<String, Object> toolContextMap = toolContext.getContext();
        String userToken = toolContextMap.get(USER_TOKEN).toString();

        if (StringUtils.isBlank(userToken)) {
            log.error("No user token found in context");
            throw new RuntimeException("No user token provided for tool call.");
        }

        log.info("User token added to reactive context.");
        Map<String, Object> filteredContextMap = new HashMap<>(toolContextMap);
        filteredContextMap.remove(USER_TOKEN);
        ToolContext filteredToolContext = new  ToolContext(filteredContextMap);

        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put(USER_TOKEN, userToken);

        Context reactiveContext = Context.of(SECURITY_CONTEXT_KEY, contextMap);
        TOOL_CALL_CONTEXT.set(reactiveContext);

        try {
            return delegate.call(toolCallInput, filteredToolContext);
        } finally {
            TOOL_CALL_CONTEXT.remove();
        }
    }

    /**
     * Gets the reactive context stored in ThreadLocal.
     * This is called by the WebClient filter to access the security context.
     */
    public static Context toolCallContext() {
        Context context = TOOL_CALL_CONTEXT.get();
        return context != null ? context : Context.empty();
    }

    /**
     * Checks if a reactive context is available in ThreadLocal.
     */
    public static boolean hasContext() {
        return TOOL_CALL_CONTEXT.get() != null;
    }
}