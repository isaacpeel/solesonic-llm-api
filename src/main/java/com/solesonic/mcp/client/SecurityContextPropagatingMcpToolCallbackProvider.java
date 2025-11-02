
package com.solesonic.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.lang.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A ToolCallbackProvider that wraps MCP tools with security context propagation.
 * This ensures that user authentication information is available during tool execution.
 */
public class SecurityContextPropagatingMcpToolCallbackProvider implements ToolCallbackProvider {
    private static final Logger log = LoggerFactory.getLogger(SecurityContextPropagatingMcpToolCallbackProvider.class);

    private final McpSyncClient mcpClient;
    private final List<ToolCallback> toolCallbacks;

    public SecurityContextPropagatingMcpToolCallbackProvider(McpSyncClient mcpClient) {
        this.mcpClient = mcpClient;
        this.toolCallbacks = new ArrayList<>();
        initializeTools();
    }

    private void initializeTools() {
        try {
            List<Tool> tools = mcpClient.listTools().tools();
            log.info("Initializing {} MCP tools with security context propagation", tools.size());

            for (Tool tool : tools) {
                SecurityContextPropagatingMcpToolCallback callback =
                        new SecurityContextPropagatingMcpToolCallback(mcpClient, tool);
                toolCallbacks.add(callback);
                log.debug("Wrapped MCP tool: {} with security context propagation", tool.name());
            }
        } catch (Exception e) {
            log.error("Failed to initialize MCP tools", e);
        }
    }

    @Override
    @NonNull
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks.toArray(new ToolCallback[0]);
    }
}