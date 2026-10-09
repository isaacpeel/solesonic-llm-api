package com.solesonic.service.chat;

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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatModelSelectionTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    private ChatModelSelection chatModelSelection;
    private UUID chatId;

    @BeforeEach
    void setUp() {
        chatModelSelection = new ChatModelSelection(redisTemplate);
        chatId = UUID.randomUUID();
    }

    /**
     * The selection lasts until the user changes it, so it is written without an expiry.
     */
    @Test
    void setWritesTheModelUnderTheChatsKeyWithoutExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.set("chat:model:" + chatId, "openai/gpt-4o")).thenReturn(Mono.just(true));

        StepVerifier.create(chatModelSelection.set(chatId, "openai/gpt-4o"))
                .verifyComplete();

        verify(valueOperations).set("chat:model:" + chatId, "openai/gpt-4o");
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getReturnsTheStoredModel() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:model:" + chatId)).thenReturn(Mono.just("openai/gpt-4o"));

        StepVerifier.create(chatModelSelection.get(chatId))
                .expectNext("openai/gpt-4o")
                .verifyComplete();
    }

    @Test
    void getIsEmptyForAChatWithNoSelection() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:model:" + chatId)).thenReturn(Mono.empty());

        StepVerifier.create(chatModelSelection.get(chatId))
                .verifyComplete();
    }

    @Test
    void getReadsOnlyItsOwnChatsKey() {
        UUID otherChatId = UUID.randomUUID();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chat:model:" + otherChatId)).thenReturn(Mono.empty());

        StepVerifier.create(chatModelSelection.get(otherChatId))
                .verifyComplete();

        verify(valueOperations, never()).get("chat:model:" + chatId);
    }

    @Test
    void clearDeletesTheChatsKey() {
        when(redisTemplate.delete("chat:model:" + chatId)).thenReturn(Mono.just(1L));

        StepVerifier.create(chatModelSelection.clear(chatId))
                .verifyComplete();

        verify(redisTemplate).delete("chat:model:" + chatId);
    }
}
