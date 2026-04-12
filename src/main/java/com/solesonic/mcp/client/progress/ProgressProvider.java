package com.solesonic.mcp.client.progress;

import com.solesonic.service.chat.ElicitationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class ProgressProvider {
    private static final Logger log = LoggerFactory.getLogger(ProgressProvider.class);

    private final ElicitationService elicitationService;

    public ProgressProvider(ElicitationService elicitationService) {
        this.elicitationService = elicitationService;
    }

    @SuppressWarnings({"unused", "UnusedReturnValue"})
    @McpProgress(clients = {"solesonic"})
    public Mono<Void> handleProgressNotification(McpSchema.ProgressNotification progressNotification) {
        log.info("Handling progress notification: {}", progressNotification);
        Object progressTokenObject = progressNotification.progressToken();

        String progressToken = progressTokenObject == null ? null : progressTokenObject.toString();

        if (progressToken == null) {
            log.info("Ignoring progress notification with missing progress token");
            return Mono.empty();
        }

        UUID chatId;

        try {
            chatId = UUID.fromString(progressToken);
        } catch (IllegalArgumentException illegalArgumentException) {
            log.info("Ignoring progress notification with non-UUID progress token: {}", progressToken);
            return Mono.empty();
        }

        elicitationService.emitProgress(chatId, progressNotification);
        return Mono.empty();
    }
}