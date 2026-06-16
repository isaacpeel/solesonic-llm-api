package com.solesonic.model.prompt;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;

public record ToolSlashCommand(String command, String name, String description, McpSchema.Tool tool)
        implements SlashCommand {

    public ToolSlashCommand(McpSchema.Tool mcpTool) {
        this(mcpTool.name(), mcpTool.name(), mcpTool.description(), mcpTool);
    }

    public ToolCallback callback(McpSyncClient mcpClient) {
        return SyncMcpToolCallback.builder()
                .mcpClient(mcpClient)
                .tool(tool)
                .build();
    }
}
