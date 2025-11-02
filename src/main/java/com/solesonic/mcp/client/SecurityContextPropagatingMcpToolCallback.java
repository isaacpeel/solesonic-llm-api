package com.solesonic.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
public class SecurityContextPropagatingMcpToolCallback implements ToolCallback {
    private static final Logger log = LoggerFactory.getLogger(SecurityContextPropagatingMcpToolCallback.class);

    public static final String SECURITY_CONTEXT_KEY = "SECURITY_CONTEXT";
    private static final ThreadLocal<Context> REACTIVE_CONTEXT = new ThreadLocal<>();

    private final SyncMcpToolCallback delegate;

    public SecurityContextPropagatingMcpToolCallback(McpSyncClient mcpClient, Tool tool) {
        this.delegate = SyncMcpToolCallback.builder()
                .mcpClient(mcpClient)
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
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();

        if (authentication != null) {
            log.debug("Captured security context for tool execution: {}",
                    authentication.getName());

            // Store security context in ThreadLocal so WebClient filter can access it
            Map<String, Object> contextMap = new HashMap<>();
            contextMap.put("authentication", authentication);

            if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                contextMap.put("jwtAuthenticationToken", jwtAuth);
            } else if (authentication.getPrincipal() instanceof Jwt jwt) {
                contextMap.put("jwt", jwt);
            }

            // Create a reactive context with the security information
            Context reactiveContext = Context.of(SECURITY_CONTEXT_KEY, contextMap);
            REACTIVE_CONTEXT.set(reactiveContext);

            try {
                // Execute the delegate with context available in ThreadLocal
                return delegate.call(toolCallInput, toolContext);
            } finally {
                // Clean up ThreadLocal
                REACTIVE_CONTEXT.remove();
            }
        } else {
            log.warn("No security context available for tool execution");
            return delegate.call(toolCallInput, toolContext);
        }
    }

    /**
     * Gets the reactive context stored in ThreadLocal.
     * This is called by the WebClient filter to access the security context.
     */
    public static Context getReactiveContext() {
        Context context = REACTIVE_CONTEXT.get();
        return context != null ? context : Context.empty();
    }

    /**
     * Checks if a reactive context is available in ThreadLocal.
     */
    public static boolean hasReactiveContext() {
        return REACTIVE_CONTEXT.get() != null;
    }
}