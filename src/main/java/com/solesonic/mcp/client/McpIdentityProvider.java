package com.solesonic.mcp.client;

import com.solesonic.service.image.GeneratedImageToolInterceptor;
import com.solesonic.service.image.ReferenceImageInjector;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A ToolCallbackProvider that wraps MCP tools with security context propagation.
 * This ensures that user authentication information is available during tool execution.
 */
@NullMarked
public class McpIdentityProvider implements ToolCallbackProvider {
    private static final Logger log = LoggerFactory.getLogger(McpIdentityProvider.class);

    private final McpSyncClient mcpClient;
    private final List<ToolCallback> toolCallbacks;
    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final GeneratedImageToolInterceptor generatedImageToolInterceptor;
    private final ReferenceImageInjector referenceImageInjector;
    private final Map<String, Tool> toolsByName;

    public McpIdentityProvider(McpSyncClient mcpClient,
                               JwtDecoder jwtDecoder,
                               JwtAuthenticationConverter jwtAuthenticationConverter,
                               GeneratedImageToolInterceptor generatedImageToolInterceptor,
                               ReferenceImageInjector referenceImageInjector) {
        this.mcpClient = mcpClient;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.generatedImageToolInterceptor = generatedImageToolInterceptor;
        this.referenceImageInjector = referenceImageInjector;
        this.toolCallbacks = new ArrayList<>();
        this.toolsByName = new LinkedHashMap<>();
        initializeToolCallbacks();
    }

    private void initializeToolCallbacks() {
        try {
            Map<Tool, ToolCallback> rawCallbacks = allMcpToolCallbacks();

            log.debug("Initializing {} MCP tools with security context propagation", rawCallbacks.size());

            for (Map.Entry<Tool, ToolCallback> rawCallback : rawCallbacks.entrySet()) {
                toolCallbacks.add(new IdentityToolCallback(rawCallback.getValue(), jwtDecoder,
                        jwtAuthenticationConverter, generatedImageToolInterceptor, referenceImageInjector,
                        GeneratedImageToolInterceptor.isTaggedImageTool(rawCallback.getKey())));
                log.debug("Wrapped MCP tool: {}", rawCallback.getKey().name());
            }
        } catch (Exception exception) {
            log.error("Failed to initialize MCP tools", exception);
        }
    }

    private Map<Tool, ToolCallback> allMcpToolCallbacks() {
        McpSchema.ListToolsResult listToolsResult = mcpClient.listTools();
        List<Tool> tools = Objects.requireNonNull(listToolsResult).tools();

        log.info("Found {} MCP tools from client", tools.size());

        Map<Tool, ToolCallback> callbacks = new LinkedHashMap<>();

        for (Tool tool : tools) {
            log.info("Available MCP tool: {}", tool.name());
            toolsByName.put(tool.name(), tool);
            callbacks.put(tool, SyncMcpToolCallback.builder()
                    .mcpClient(mcpClient)
                    .tool(tool)
                    .build());
        }

        return callbacks;
    }

    /**
     * The MCP server's own description of a tool, as listed at startup — the original input schema
     * and {@code _meta}, before {@link IdentityToolCallback} trims anything from what the model is
     * shown.
     */
    public Optional<Tool> tool(String toolName) {
        return Optional.ofNullable(toolsByName.get(toolName));
    }

    /**
     * Every tool the MCP server listed at startup, in its order.
     */
    public List<Tool> tools() {
        return List.copyOf(toolsByName.values());
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks.toArray(new ToolCallback[0]);
    }

    /**
     * The identity-wrapped subset of {@link #getToolCallbacks()} whose name is in {@code toolNames}.
     * <p>
     * Spring AI's {@code ChatClient} only ever adds request-level tools on top of a builder's
     * default tools, so a caller that must offer fewer than the full MCP catalog cannot rely on
     * {@code ChatClient.Builder#defaultTools} at all — it has to build its own list per request.
     * This is the one place that list is assembled from.
     */
    public List<ToolCallback> getToolCallbacks(Set<String> toolNames) {
        return toolCallbacks.stream()
                .filter(toolCallback -> toolNames.contains(toolCallback.getToolDefinition().name()))
                .toList();
    }
}
