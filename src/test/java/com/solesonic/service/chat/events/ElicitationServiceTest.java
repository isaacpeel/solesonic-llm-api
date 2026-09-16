package com.solesonic.service.chat.events;

import com.solesonic.service.chat.ChatMessageService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElicitationServiceTest {

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    private ElicitationService elicitationService;
    private Sinks.Many<String> messageRelay;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
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

    /**
     * A stop button has no pending elicitation to answer, so the signal {@code cancelEvents} listens
     * for must be reachable on its own — not only as a side effect of declining a form.
     */
    @Test
    void cancelChatPublishesTheCancelSignalOnTheChatsEventsChannel() {
        UUID chatId = UUID.randomUUID();
        AtomicReference<ServerSentEvent<?>> received = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);

        elicitationService.registerChat(chatId)
                .take(1)
                .subscribe(serverSentEvent -> {
                    received.set(serverSentEvent);
                    countDownLatch.countDown();
                });

        StepVerifier.create(elicitationService.cancelChat(chatId)).verifyComplete();

        boolean eventReceived;
        try {
            eventReceived = countDownLatch.await(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        }

        assertThat(eventReceived).isTrue();

        ServerSentEvent<?> serverSentEvent = received.get();
        assertThat(serverSentEvent.event()).isEqualTo(ElicitationService.CANCEL_ACTION);
        assertThat(serverSentEvent.data()).isEqualTo(ElicitationService.CANCEL_ACTION);
    }

    /**
     * Pub/sub delivers only to a subscriber already attached — a signal sent before that
     * subscription is live reaches no one. The receiver count reflects that, but the call itself
     * must still complete rather than error.
     */
    @Test
    void cancelChatCompletesEvenWhenNoOneIsListening() {
        UUID chatId = UUID.randomUUID();

        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(0L));

        StepVerifier.create(elicitationService.cancelChat(chatId)).verifyComplete();
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
