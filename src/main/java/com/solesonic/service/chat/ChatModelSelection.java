package com.solesonic.service.chat;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The chat model a conversation was switched to with {@code /model}, one Redis key per chat.
 * <p>
 * Written without an expiry, because the choice lasts until the user changes it; {@link ChatService}
 * removes it when the chat is deleted. A chat with no key uses the configured default.
 */
@Service
public class ChatModelSelection {
    private static final String KEY_PREFIX = "chat:model:";

    private final ReactiveStringRedisTemplate redisTemplate;

    public ChatModelSelection(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<String> get(UUID chatId) {
        return redisTemplate.opsForValue().get(key(chatId));
    }

    public Mono<Void> set(UUID chatId, String model) {
        return redisTemplate.opsForValue().set(key(chatId), model).then();
    }

    public Mono<Void> clear(UUID chatId) {
        return redisTemplate.delete(key(chatId)).then();
    }

    private String key(UUID chatId) {
        return KEY_PREFIX + chatId;
    }
}
