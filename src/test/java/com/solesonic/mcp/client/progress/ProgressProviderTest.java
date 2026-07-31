package com.solesonic.mcp.client.progress;

import com.solesonic.model.image.ImageGenerationProgress;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.image.ImageGenerationProgressBroker;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgressProviderTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private ImageGenerationProgressBroker imageGenerationProgressBroker;

    @InjectMocks
    private ProgressProvider progressProvider;

    @Test
    void handleProgressNotificationShouldEmitProgressWhenProgressTokenIsValidChatId() {
        UUID chatId = UUID.randomUUID();

        McpSchema.ProgressNotification progressNotification = McpSchema.ProgressNotification.builder(chatId.toString(), 0.5d)
                .total(1.0d)
                .message("half-way")
                .build();

        when(imageGenerationProgressBroker.emit(any(UUID.class), any(ImageGenerationProgress.class)))
                .thenReturn(false);

        progressProvider.handleProgressNotification(progressNotification);

        verify(notificationService).emitProgress(chatId, progressNotification);
    }

    /**
     * The broker claiming the token is what keeps image progress out of chat history — a generation
     * has no chat, so a SYSTEM message written for one would be an orphan.
     */
    @Test
    void handleProgressNotificationShouldNotReachChatWhenTokenNamesAnImageGeneration() {
        UUID generationId = UUID.randomUUID();

        McpSchema.ProgressNotification progressNotification = McpSchema.ProgressNotification.builder(generationId.toString(), 85.0d)
                .total(100.0d)
                .message("Generating…")
                .build();

        when(imageGenerationProgressBroker.emit(any(UUID.class), any(ImageGenerationProgress.class)))
                .thenReturn(true);

        progressProvider.handleProgressNotification(progressNotification);

        ArgumentCaptor<ImageGenerationProgress> progressCaptor = ArgumentCaptor.forClass(ImageGenerationProgress.class);
        verify(imageGenerationProgressBroker).emit(eq(generationId), progressCaptor.capture());

        assertThat(progressCaptor.getValue().percent()).isEqualTo(85);
        assertThat(progressCaptor.getValue().message()).isEqualTo("Generating…");

        verify(notificationService, never()).emitProgress(any(UUID.class), any(McpSchema.ProgressNotification.class));
    }

    /**
     * A notification without a total carries no percentage rather than one read against an unknown
     * scale.
     */
    @Test
    void handleProgressNotificationShouldCarryNoPercentWhenTotalIsAbsent() {
        UUID generationId = UUID.randomUUID();

        McpSchema.ProgressNotification progressNotification =
                McpSchema.ProgressNotification.builder(generationId.toString(), 15.0d)
                        .message("Queued")
                        .build();

        when(imageGenerationProgressBroker.emit(any(UUID.class), any(ImageGenerationProgress.class)))
                .thenReturn(true);

        progressProvider.handleProgressNotification(progressNotification);

        ArgumentCaptor<ImageGenerationProgress> progressCaptor = ArgumentCaptor.forClass(ImageGenerationProgress.class);
        verify(imageGenerationProgressBroker).emit(any(UUID.class), progressCaptor.capture());

        assertThat(progressCaptor.getValue().percent()).isNull();
    }

    @Test
    void handleProgressNotificationShouldIgnoreProgressWhenTokenIsNotUuid() {
        McpSchema.ProgressNotification progressNotification = McpSchema.ProgressNotification.builder("not-a-uuid", 0.5d)
                .total(1.0d)
                .message("half-way")
                .build();

        progressProvider.handleProgressNotification(progressNotification);

        verify(imageGenerationProgressBroker, never()).emit(any(UUID.class), any(ImageGenerationProgress.class));
        verify(notificationService, never()).emitProgress(any(UUID.class), any(McpSchema.ProgressNotification.class));
    }
}
