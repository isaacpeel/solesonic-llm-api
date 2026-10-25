package com.solesonic.service.chat.events;

import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.push.ElicitationReminderScheduler;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    private NotificationService notificationService;
    private ElicitationService elicitationService;
    private Sinks.Many<String> messageRelay;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        messageRelay = Sinks.many().multicast().onBackpressureBuffer();

        Flux<ReactiveSubscription.Message<String, String>> listenerFlux = messageRelay.asFlux()
                .map(body -> new FixedChannelMessage("channel", body));

        lenient().doReturn(listenerFlux).when(redisTemplate).listenToChannel(any(String.class));

        lenient().when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    messageRelay.tryEmitNext(invocation.getArgument(1));
                    return Mono.just(1L);
                });

        SideChannelPublisher sideChannelPublisher = new SideChannelPublisher(jsonMapper, redisTemplate);

        notificationService = new NotificationService(jsonMapper, chatMessageService, sideChannelPublisher);
        elicitationService = new ElicitationService(jsonMapper, chatMessageService, redisTemplate,
                sideChannelPublisher, mock(ElicitationReminderScheduler.class));
    }

    @Test
    void emitProgressShouldSendProgressEventToRegisteredChatSink() {
        UUID chatId = UUID.randomUUID();
        AtomicReference<SideChannelMessage> received = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);

        elicitationService.registerChat(chatId)
                .take(1)
                .subscribe(message -> {
                    received.set(message);
                    countDownLatch.countDown();
                });

        McpSchema.ProgressNotification progressNotification = McpSchema.ProgressNotification.builder(chatId.toString(), 0.5d)
                .total(1.0d)
                .message("half-way")
                .build();

        notificationService.emitProgress(chatId, progressNotification);

        try {
            boolean eventReceived = countDownLatch.await(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
            assertThat(eventReceived).isTrue();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        }

        SideChannelMessage message = received.get();

        assertThat(message).isNotNull();
        assertThat(message.event()).isEqualTo(NotificationService.PROGRESS);
        assertThat(message.data().isObject()).isTrue();
        assertThat(message.data().path(NotificationService.CHAT_ID).asString()).isEqualTo(chatId.toString());
        assertThat(message.data().path("message").asString()).isEqualTo("half-way");
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
