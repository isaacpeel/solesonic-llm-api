package com.solesonic.redis.service;

import com.solesonic.config.JacksonConfig;
import com.solesonic.redis.publisher.ChatStreamPublisher;
import com.solesonic.redis.subscriber.ChatStreamSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = new JacksonConfig().jsonMapper();
        redisStreamService = new RedisStreamService(chatStreamPublisher, chatStreamSubscriber, jsonMapper, redisTemplate);
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
