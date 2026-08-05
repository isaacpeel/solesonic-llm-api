package com.solesonic.mcp.client.progress;

import com.solesonic.model.image.ImageGenerationProgress;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.image.ImageGenerationProgressBroker;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProgressProvider {
    private static final Logger log = LoggerFactory.getLogger(ProgressProvider.class);

    private final NotificationService notificationService;
    private final ImageGenerationProgressBroker imageGenerationProgressBroker;

    public ProgressProvider(NotificationService notificationService,
                            ImageGenerationProgressBroker imageGenerationProgressBroker) {
        this.notificationService = notificationService;
        this.imageGenerationProgressBroker = imageGenerationProgressBroker;
    }

    @SuppressWarnings("unused")
    @McpProgress(clients = {"solesonic", "mcp-client - solesonic"})
    public void handleProgressNotification(McpSchema.ProgressNotification progressNotification) {
        log.info("handle progress notification");
        Object progressTokenObject = progressNotification.progressToken();

        String progressToken = progressTokenObject == null ? null : progressTokenObject.toString();

        if (progressToken == null) {
            log.info("Ignoring progress notification with missing progress token");
            return;
        }

        UUID progressTokenId;

        try {
            progressTokenId = UUID.fromString(progressToken);
        } catch (IllegalArgumentException illegalArgumentException) {
            log.info("Ignoring progress notification with non-UUID progress token: {}", progressToken);
            return;
        }

        //An image generation has no chat, so its progress must not become chat history. The broker
        //claims the notification when the token names a generation it has open, and declines it
        //otherwise — which is what lets one MCP callback serve both without either knowing about
        //the other.
        if (imageGenerationProgressBroker.emit(progressTokenId, imageGenerationProgress(progressNotification))) {
            return;
        }

        notificationService.emitProgress(progressTokenId, progressNotification);
    }

    /**
     * The tool reports against a total of 100, so its {@code progress} value is the percentage read
     * directly. A notification without a total carries no percentage rather than a misleading one.
     */
    private static ImageGenerationProgress imageGenerationProgress(McpSchema.ProgressNotification progressNotification) {
        Double total = progressNotification.total();
        Double progress = progressNotification.progress();

        Integer percent = (total == null || progress == null) ? null : (int) Math.round(progress);

        return new ImageGenerationProgress(percent, progressNotification.message());
    }
}