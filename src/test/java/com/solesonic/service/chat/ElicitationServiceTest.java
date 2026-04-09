package com.solesonic.service.chat;

import com.solesonic.service.ollama.ChatMessageService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ElicitationServiceTest {

    @Mock
    private ChatMessageService chatMessageService;

    private ElicitationService elicitationService;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        elicitationService = new ElicitationService(jsonMapper, chatMessageService);
    }

    @Test
    void emitProgressShouldSendProgressEventToRegisteredChatSink() {
        UUID chatId = UUID.randomUUID();
        AtomicReference<ServerSentEvent<?>> serverSentEventRef = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);

        elicitationService.registerChat(chatId)
                .take(1)
                .subscribe(serverSentEvent -> {
                    serverSentEventRef.set(serverSentEvent);
                    countDownLatch.countDown();
                });

        McpSchema.ProgressNotification progressNotification = new McpSchema.ProgressNotification(chatId.toString(), 0.5d, 1.0d, "half-way");

        elicitationService.emitProgress(chatId, progressNotification);

        try {
            boolean eventReceived = countDownLatch.await(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
            assertThat(eventReceived).isTrue();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        }

        ServerSentEvent<?> serverSentEvent = serverSentEventRef.get();

        assertThat(serverSentEvent).isNotNull();
        assertThat(serverSentEvent.event()).isEqualTo(ElicitationService.PROGRESS);
        assertThat(serverSentEvent.data()).isInstanceOf(Map.class);

        Map<String, Object> eventData = new HashMap<>((Map<String, Object>) serverSentEvent.data());
        assertThat(eventData).containsEntry(ElicitationService.CHAT_ID, chatId.toString());
    }
}