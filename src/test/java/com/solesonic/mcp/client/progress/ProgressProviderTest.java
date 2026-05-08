package com.solesonic.mcp.client.progress;

import com.solesonic.service.chat.ElicitationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProgressProviderTest {

    @Mock
    private ElicitationService elicitationService;

    @InjectMocks
    private ProgressProvider progressProvider;

    @Test
    void handleProgressNotificationShouldEmitProgressWhenProgressTokenIsValidChatId() {
        UUID chatId = UUID.randomUUID();
        McpSchema.ProgressNotification progressNotification = new McpSchema.ProgressNotification(chatId.toString(), 0.5d, 1.0d, "half-way");

        progressProvider.handleProgressNotification(progressNotification);

        verify(elicitationService).emitProgress(chatId, progressNotification);
    }

    @Test
    void handleProgressNotificationShouldIgnoreProgressWhenTokenIsNotUuid() {
        McpSchema.ProgressNotification progressNotification = new McpSchema.ProgressNotification("not-a-uuid", 0.5d, 1.0d, "half-way");

        progressProvider.handleProgressNotification(progressNotification);

        verify(elicitationService, never()).emitProgress(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}