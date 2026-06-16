package com.solesonic.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A ToolCallbackProvider that wraps MCP tools with security context propagation.
 * This ensures that user authentication information is available during tool execution.
 */
@NullMarked
public class McpIdentityProvider implements ToolCallbackProvider {
    private static final Logger log = LoggerFactory.getLogger(McpIdentityProvider.class);

    private final McpSyncClient mcpClient;
    private final List<ToolCallback> toolCallbacks;

    public McpIdentityProvider(McpSyncClient mcpClient) {
        this.mcpClient = mcpClient;
        this.toolCallbacks = new ArrayList<>();
        initializeToolCallbacks();
    }

    private void initializeToolCallbacks() {
        try {
            List<ToolCallback> rawCallbacks = allMcpToolCallbacks();

            log.debug("Initializing {} MCP tools with security context propagation", rawCallbacks.size());

            for (ToolCallback rawCallback : rawCallbacks) {
                toolCallbacks.add(new IdentityToolCallback(rawCallback));
                log.debug("Wrapped MCP tool: {}", rawCallback.getToolDefinition().name());
            }
        } catch (Exception exception) {
            log.error("Failed to initialize MCP tools", exception);
        }
    }

    private List<ToolCallback> allMcpToolCallbacks() {
        List<Tool> tools = Objects.requireNonNull(mcpClient.listTools()).tools();

        log.info("Found {} MCP tools from client", tools.size());
        tools.forEach(tool -> log.debug("Available MCP tool: {}", tool.name()));

        return tools.stream()
                .<ToolCallback>map(tool -> SyncMcpToolCallback.builder()
                        .mcpClient(mcpClient)
                        .tool(tool)
                        .build())
                .toList();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks.toArray(new ToolCallback[0]);
    }
}
