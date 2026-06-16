package com.solesonic.mcp.client.progress;

import com.solesonic.service.chat.events.NotificationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProgressProviderTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ProgressProvider progressProvider;

    @Test
    void handleProgressNotificationShouldEmitProgressWhenProgressTokenIsValidChatId() {
        UUID chatId = UUID.randomUUID();

        McpSchema.ProgressNotification progressNotification = McpSchema.ProgressNotification.builder(chatId.toString(), 0.5d)
                .total(1.0d)
                .message("half-way")
                .build();

        progressProvider.handleProgressNotification(progressNotification);

        verify(notificationService).emitProgress(chatId, progressNotification);
    }

//    @Test
//    void handleProgressNotificationShouldIgnoreProgressWhenTokenIsNotUuid() {
//        McpSchema.ProgressNotification progressNotification = McpSchema.ProgressNotification.builder("not-a-uuid", 0.5d)
//                .total(1.0d)
//                .message("half-way")
//                .build();
//
//        progressProvider.handleProgressNotification(progressNotification);
//
//        verify(notificationService, never()).emitProgress(org.mockito.ArgumentMatchers.any(McpSchema.ProgressNotification.class), org.mockito.ArgumentMatchers.any());
//    }
}