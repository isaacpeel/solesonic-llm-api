package com.solesonic.service.chat;

import com.solesonic.service.ollama.ChatMessageService;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.springframework.data.redis.core.ReactiveValueOperations;

@ExtendWith(MockitoExtension.class)
class ElicitationServiceTest {
    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    private ElicitationService elicitationService;
    private JsonMapper jsonMapper;
    private Sinks.Many<String> messageRelay;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        messageRelay = Sinks.many().multicast().onBackpressureBuffer();

        lenient().doReturn(valueOperations).when(redisTemplate).opsForValue();

        Flux<ReactiveSubscription.Message<String, String>> listenerFlux = messageRelay.asFlux()
                .map(body -> new FixedChannelMessage("channel", body));

        lenient().doReturn(listenerFlux).when(redisTemplate).listenToChannel(any(String.class));

        lenient().when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    messageRelay.tryEmitNext(invocation.getArgument(1));
                    return Mono.just(1L);
                });

        elicitationService = new ElicitationService(jsonMapper, chatMessageService, redisTemplate);
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
        assertThat(serverSentEvent.data()).isInstanceOf(String.class);

        assert serverSentEvent.data() != null;
        Object data = serverSentEvent.data();

        Map<String, Object> eventData = jsonMapper.readValue(data.toString(), new TypeReference<>() {});

        assertThat(eventData).containsEntry(ElicitationService.CHAT_ID, chatId.toString());
    }

    private record FixedChannelMessage(String channelName, String messageBody)
            implements ReactiveSubscription.Message<String, String> {

        @Override
        public @NonNull String getChannel() {
            return channelName;
        }

        @Override
        public @NonNull String getMessage() {
            return messageBody;
        }
    }
}
