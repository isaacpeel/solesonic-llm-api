package com.solesonic.service.chat;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * The selection of several chats in one round trip. Every requested chat is a key of the result: a chat with
     * no selection maps to null (the default), so a chat that is absent from the result was never read, which a
     * failed read cannot be mistaken for.
     */
    public Mono<Map<UUID, String>> getAll(Collection<UUID> chatIds) {
        List<UUID> ordered = List.copyOf(new LinkedHashSet<>(chatIds));

        if (ordered.isEmpty()) {
            return Mono.just(Map.of());
        }

        return redisTemplate.opsForValue().multiGet(ordered.stream().map(this::key).toList())
                .map(values -> {
                    Map<UUID, String> selections = new HashMap<>();

                    for (int index = 0; index < ordered.size(); index++) {
                        selections.put(ordered.get(index), index < values.size() ? values.get(index) : null);
                    }

                    return selections;
                });
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
