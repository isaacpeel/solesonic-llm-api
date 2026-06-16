package com.solesonic.service.a2a;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2AStickyAgentServiceTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    private A2AStickyAgentService stickyAgentService;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        stickyAgentService = new A2AStickyAgentService(redisTemplate);
        chatId = UUID.randomUUID();
    }

    @Test
    void activateWritesAgentNameWithTtlToCorrectKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.set(any(), any(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(stickyAgentService.activate(chatId, "weather"))
                .verifyComplete();

        verify(valueOperations).set(
                eq("chat:a2a:active-agent:" + chatId),
                eq("weather"),
                eq(Duration.ofHours(24)));
    }

    @Test
    void getActiveAgentReturnsPresentOptionalWhenKeyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(eq("chat:a2a:active-agent:" + chatId)))
                .thenReturn(Mono.just("weather"));

        StepVerifier.create(stickyAgentService.getActiveAgent(chatId))
                .expectNext(Optional.of("weather"))
                .verifyComplete();
    }

    @Test
    void getActiveAgentReturnsEmptyOptionalWhenKeyMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(eq("chat:a2a:active-agent:" + chatId)))
                .thenReturn(Mono.empty());

        StepVerifier.create(stickyAgentService.getActiveAgent(chatId))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    void deactivateDeletesCorrectKey() {
        when(redisTemplate.delete(eq("chat:a2a:active-agent:" + chatId))).thenReturn(Mono.just(1L));

        StepVerifier.create(stickyAgentService.deactivate(chatId))
                .verifyComplete();

        verify(redisTemplate).delete(eq("chat:a2a:active-agent:" + chatId));
    }

    @Test
    void activateTaskWritesTaskIdWithTtlToCorrectKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.set(any(), any(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(stickyAgentService.activateTask(chatId, "task-abc-123"))
                .verifyComplete();

        verify(valueOperations).set(
                eq("chat:a2a:active-task:" + chatId),
                eq("task-abc-123"),
                eq(Duration.ofHours(24)));
    }

    @Test
    void getActiveTaskIdReturnsPresentOptionalWhenKeyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(eq("chat:a2a:active-task:" + chatId)))
                .thenReturn(Mono.just("task-abc-123"));

        StepVerifier.create(stickyAgentService.getActiveTaskId(chatId))
                .expectNext(Optional.of("task-abc-123"))
                .verifyComplete();
    }

    @Test
    void getActiveTaskIdReturnsEmptyOptionalWhenKeyMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(eq("chat:a2a:active-task:" + chatId)))
                .thenReturn(Mono.empty());

        StepVerifier.create(stickyAgentService.getActiveTaskId(chatId))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    void deactivateTaskDeletesCorrectKey() {
        when(redisTemplate.delete(eq("chat:a2a:active-task:" + chatId))).thenReturn(Mono.just(1L));

        StepVerifier.create(stickyAgentService.deactivateTask(chatId))
                .verifyComplete();

        verify(redisTemplate).delete(eq("chat:a2a:active-task:" + chatId));
    }
}
