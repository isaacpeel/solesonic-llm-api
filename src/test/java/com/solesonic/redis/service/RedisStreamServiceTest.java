package com.solesonic.redis.service;

import com.solesonic.config.JacksonConfig;
import com.solesonic.config.push.PushSettings;
import com.solesonic.redis.publisher.ChatStreamPublisher;
import com.solesonic.redis.subscriber.ChatStreamSubscriber;
import com.solesonic.service.push.PushPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins that a Redis read failure — the transport underneath an in-flight or resumed SSE response —
 * surfaces as one terminal {@code RUN_ERROR} frame rather than killing the connection silently.
 */
@ExtendWith(MockitoExtension.class)
class RedisStreamServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private ChatStreamPublisher chatStreamPublisher;

    @Mock
    private ChatStreamSubscriber chatStreamSubscriber;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    private RedisStreamService redisStreamService;

    @Mock
    private ReactiveZSetOperations<String, String> zSetOperations;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = new JacksonConfig().jsonMapper();

        lenient().doReturn(zSetOperations).when(redisTemplate).opsForZSet();
        lenient().when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        lenient().when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        PushSettings pushSettings = new PushSettings(true, Set.of("fcm.googleapis.com"), 3, 5, Duration.ofSeconds(120),
                Duration.ofSeconds(600), Duration.ofSeconds(45), Duration.ofSeconds(15));
        PushPresenceService pushPresenceService = new PushPresenceService(redisTemplate, pushSettings, Clock.systemUTC());

        redisStreamService = new RedisStreamService(chatStreamPublisher, chatStreamSubscriber, jsonMapper, redisTemplate,
                pushPresenceService);
    }

    /**
     * The turn's own stream and a {@code GET} resume both read through here, so this one wrap is what
     * makes every reader count as watching the chat.
     */
    @Test
    void aReaderIsCountedAsWatchingWhileSubscribed() {
        when(chatStreamSubscriber.subscribe(anyString(), any()))
                .thenReturn(Flux.just(ServerSentEvent.builder("hello").event("TEXT_MESSAGE_CONTENT").build()));

        StepVerifier.create(redisStreamService.subscribe(CHAT_ID, USER_ID, null))
                .expectNextCount(1)
                .verifyComplete();

        String presenceKey = "push:presence:" + USER_ID + ":" + CHAT_ID;
        verify(zSetOperations, times(2)).add(eq(presenceKey), anyString(), anyDouble());
    }

    @Test
    void aPresenceFailureLeavesTheStreamIntact() {
        ServerSentEvent<String> chunk = ServerSentEvent.builder("hello").event("TEXT_MESSAGE_CONTENT").build();

        when(chatStreamSubscriber.subscribe(anyString(), any())).thenReturn(Flux.just(chunk));
        when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.error(new RedisConnectionFailureException("redis down")));

        StepVerifier.create(redisStreamService.subscribe(CHAT_ID, USER_ID, null))
                .expectNext(chunk)
                .verifyComplete();
    }

    @Test
    void aReadFailureBecomesOneTerminalRunErrorFrame() {
        when(chatStreamSubscriber.subscribe(anyString(), any()))
                .thenReturn(Flux.error(new RuntimeException("connection reset")));

        StepVerifier.create(redisStreamService.subscribe(CHAT_ID, USER_ID, null))
                .assertNext(serverSentEvent -> {
                    assertThat(serverSentEvent.event()).isEqualTo("RUN_ERROR");
                    assertThat(serverSentEvent.id()).isNull();
                    assertThat((String) serverSentEvent.data())
                            .contains("\"code\":\"STREAM_UNAVAILABLE\"")
                            .doesNotContain("connection reset");
                })
                .verifyComplete();
    }

    @Test
    void aSuccessfulReadNeverEmitsAFailureFrame() {
        ServerSentEvent<String> chunk = ServerSentEvent.builder("hello").event("TEXT_MESSAGE_CONTENT").build();

        when(chatStreamSubscriber.subscribe(anyString(), any())).thenReturn(Flux.just(chunk));

        StepVerifier.create(redisStreamService.subscribe(CHAT_ID, USER_ID, null))
                .expectNext(chunk)
                .verifyComplete();
    }
}
