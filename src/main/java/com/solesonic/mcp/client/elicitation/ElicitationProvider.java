package com.solesonic.mcp.client.elicitation;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpElicitation;
import org.springaicommunity.mcp.annotation.McpProgress;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ElicitationProvider {
    private static final Logger log = LoggerFactory.getLogger(ElicitationProvider.class);

    @McpElicitation(clients = {"solesonic", "mcp-client - solesonic"})
    public McpSchema.ElicitResult handleElicitationRequest(McpSchema.ElicitRequest request) {
        // Present the request to the user and gather input
        Map<String, Object> userData = Map.of();

        if (userData != null) {
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, userData);
        } else {
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null);
        }
    }

    @McpProgress(clients = {"solesonic"})
    public void handleProgressNotification(McpSchema.ProgressNotification notification) {
        double percentage = notification.progress() * 100;

        log.info("Progress: {}% - {}", String.format("%.2f", percentage), notification.message());

    }
}
