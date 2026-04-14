package com.solesonic.mcp.client;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import reactor.core.scheduler.Schedulers;

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

    private final McpAsyncClient mcpClient;
    private final List<ToolCallback> toolCallbacks;

    public McpIdentityProvider(McpAsyncClient mcpClient) {
        this.mcpClient = mcpClient;
        this.toolCallbacks = new ArrayList<>();
        initializeTools();
    }

    public McpIdentityProvider(McpAsyncClient mcpClient, String tool) {
        this.mcpClient = mcpClient;
        this.toolCallbacks = new ArrayList<>();
        initializeTool(tool);
    }

    public void initializeTool(String name) {
        try {
            Tool tool = findTool(name);

            log.info("MCP tool {} initialized with security context propagation", tool.name());

            IdentityToolCallback callback = new IdentityToolCallback(mcpClient, tool);
            toolCallbacks.add(callback);
        } catch (Exception e) {
            log.error("Failed to initialize MCP tools", e);
        }
    }

    private void initializeTools() {
        try {
            List<Tool> tools = allMcpTools();

            log.info("Initializing {} MCP tools with security context propagation", tools.size());

            for (Tool tool : tools) {
                IdentityToolCallback callback = new IdentityToolCallback(mcpClient, tool);
                toolCallbacks.add(callback);
                log.debug("Wrapped MCP tool: {} ", tool.name());
            }
        } catch (Exception e) {
            log.error("Failed to initialize MCP tools", e);
        }
    }

    private Tool findTool(String name) {
        return allMcpTools().stream()
                .filter(mcpTool -> mcpTool.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tool not found"));
    }

    private List<Tool> allMcpTools() {
        return Objects.requireNonNull(mcpClient.listTools()
                        .subscribeOn(Schedulers.boundedElastic())
                        .block())
                .tools();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return toolCallbacks.toArray(new ToolCallback[0]);
    }
}