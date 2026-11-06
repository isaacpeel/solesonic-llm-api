package com.solesonic.service.push;

import com.solesonic.config.push.PushSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushPresenceServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final String KEY = "push:presence:" + USER_ID + ":" + CHAT_ID;
    private static final double EXPIRES_AT = NOW.plusSeconds(45).toEpochMilli();
    private static final double STOPPED_EXPIRES_AT = NOW.plusSeconds(10).toEpochMilli();

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveZSetOperations<String, String> zSetOperations;

    @Captor
    private ArgumentCaptor<Range<Double>> expiredRange;

    private PushPresenceService pushPresenceService;

    @BeforeEach
    void setUp() {
        doReturn(zSetOperations).when(redisTemplate).opsForZSet();

        when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        when(zSetOperations.remove(anyString(), any())).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        PushSettings pushSettings = new PushSettings(true, Set.of("fcm.googleapis.com"), 3, 5, Duration.ofSeconds(120),
                Duration.ofSeconds(600), Duration.ofSeconds(45), Duration.ofSeconds(15));

        pushPresenceService = new PushPresenceService(redisTemplate, pushSettings, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registersAReaderForAsLongAsItIsSubscribed() {
        StepVerifier.create(pushPresenceService.track(USER_ID, CHAT_ID, Flux.just("first", "second")))
                .expectNext("first", "second")
                .verifyComplete();

        ArgumentCaptor<String> readerId = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations).add(eq(KEY), readerId.capture(), eq(EXPIRES_AT));
        verify(redisTemplate).expire(KEY, Duration.ofSeconds(45));
        verify(zSetOperations).add(KEY, readerId.getValue(), STOPPED_EXPIRES_AT);
        verify(zSetOperations, never()).remove(anyString(), any());
    }

    /**
     * A turn's own reader stops at the terminal frame, just as the turn asks whether anyone is
     * watching. A short grace keeps the person who watched the reply from being pushed about it.
     */
    @Test
    void aStoppedReaderLapsesAfterAGraceRatherThanAtOnce() {
        StepVerifier.create(pushPresenceService.track(USER_ID, CHAT_ID, Flux.never()))
                .thenCancel()
                .verify();

        ArgumentCaptor<String> readerId = ArgumentCaptor.forClass(String.class);
        verify(zSetOperations).add(eq(KEY), readerId.capture(), eq(EXPIRES_AT));
        verify(zSetOperations).add(KEY, readerId.getValue(), STOPPED_EXPIRES_AT);
    }

    /**
     * The heartbeat runs on its own clock. Keepalive comments only go out while a stream is silent,
     * so a stream busy with text would otherwise let its presence lapse.
     */
    @Test
    void keepsAReaderAliveOnEveryHeartbeat() {
        StepVerifier.withVirtualTime(() -> pushPresenceService.track(USER_ID, CHAT_ID, Flux.never()))
                .expectSubscription()
                .then(() -> verify(zSetOperations, times(1)).add(eq(KEY), anyString(), anyDouble()))
                .thenAwait(Duration.ofSeconds(15))
                .then(() -> verify(zSetOperations, times(2)).add(eq(KEY), anyString(), anyDouble()))
                .thenAwait(Duration.ofSeconds(15))
                .then(() -> verify(zSetOperations, times(3)).add(eq(KEY), anyString(), anyDouble()))
                .thenCancel()
                .verify();

        verify(redisTemplate, times(3)).expire(KEY, Duration.ofSeconds(45));
        verify(zSetOperations, times(4)).add(eq(KEY), anyString(), anyDouble());
    }

    @Test
    void aPresenceFailureNeverReachesTheStream() {
        when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.error(new RedisConnectionFailureException("redis down")));

        StepVerifier.create(pushPresenceService.track(USER_ID, CHAT_ID, Flux.just("chunk")))
                .expectNext("chunk")
                .verifyComplete();
    }

    @Test
    void anAnonymousReaderIsNotTracked() {
        StepVerifier.create(pushPresenceService.track(null, CHAT_ID, Flux.just("chunk")))
                .expectNext("chunk")
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void aChatWithALiveReaderIsWatched() {
        when(zSetOperations.removeRangeByScore(eq(KEY), any())).thenReturn(Mono.just(0L));
        when(zSetOperations.size(KEY)).thenReturn(Mono.just(1L));

        StepVerifier.create(pushPresenceService.isWatching(USER_ID, CHAT_ID))
                .expectNext(true)
                .verifyComplete();
    }

    /**
     * A reader whose expiry has passed is dropped before counting, so a client that vanished without
     * a clean disconnect stops counting once its last heartbeat lapses.
     */
    @Test
    void expiredReadersAreDroppedBeforeCounting() {
        when(zSetOperations.removeRangeByScore(eq(KEY), any())).thenReturn(Mono.just(1L));
        when(zSetOperations.size(KEY)).thenReturn(Mono.just(0L));

        StepVerifier.create(pushPresenceService.isWatching(USER_ID, CHAT_ID))
                .expectNext(false)
                .verifyComplete();

        verify(zSetOperations).removeRangeByScore(eq(KEY), expiredRange.capture());
        assertThat(expiredRange.getValue().getLowerBound().isBounded()).isFalse();
        assertThat(expiredRange.getValue().getUpperBound().getValue()).isEqualTo(Optional.of((double) NOW.toEpochMilli()));

        InOrder order = inOrder(zSetOperations);
        order.verify(zSetOperations).removeRangeByScore(eq(KEY), any());
        order.verify(zSetOperations).size(KEY);
    }

    @Test
    void aFailedCheckCountsAsNotWatching() {
        when(zSetOperations.removeRangeByScore(eq(KEY), any()))
                .thenReturn(Mono.error(new RedisConnectionFailureException("redis down")));

        StepVerifier.create(pushPresenceService.isWatching(USER_ID, CHAT_ID))
                .expectNext(false)
                .verifyComplete();
    }
}
