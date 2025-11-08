package com.solesonic.mcp.client.elicitation;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpElicitation;
import org.springaicommunity.mcp.annotation.McpProgress;
import org.springaicommunity.mcp.annotation.McpSampling;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class ElicitationProvider {
    private static final Logger log =  LoggerFactory.getLogger(ElicitationProvider.class);

    @McpElicitation(clients = {"solesonic"})
    public McpSchema.ElicitResult elicitationHandler(McpSchema.ElicitRequest request) {
        log.info("MCP ELICITATION: {}", request);
        return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of());
    }

    @McpElicitation(clients = "solesonic")
    public Mono<McpSchema.ElicitResult> handleAsyncElicitationRequest(McpSchema.ElicitRequest request) {
        return Mono.just(McpSchema.ElicitResult.builder()
                .message(McpSchema.ElicitResult.Action.valueOf("Generated response"))
                .build());
    }

    @McpProgress(clients = {"solesonic"})
    public void handleProgressNotification(McpSchema.ProgressNotification notification) {
        double percentage = notification.progress() * 100;
        System.out.printf("Progress: %.2f%% - %s%n", percentage, notification.message());
    }

    @McpSampling(clients = "solesonic")
    public Mono<McpSchema.CreateMessageResult> handleAsyncSamplingRequest(McpSchema.CreateMessageRequest request) {
        // Process the request asynchronously and return a result
        return Mono.just(McpSchema.CreateMessageResult.builder()
                .message("Generated response")
                .build());
    }
}
