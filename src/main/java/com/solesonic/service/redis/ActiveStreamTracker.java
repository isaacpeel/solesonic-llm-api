package com.solesonic.service.redis;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class ActiveStreamTracker {
    private static final String KEY = "user:active-streams";

    private final ReactiveStringRedisTemplate redisTemplate;

    public ActiveStreamTracker(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Boolean> put(UUID userId, UUID chatId) {
        return redisTemplate.opsForHash()
                .put(KEY, userId.toString(), chatId.toString());
    }

    public Mono<UUID> get(UUID userId) {
        return redisTemplate.<String, String>opsForHash()
                .get(KEY, userId.toString())
                .map(UUID::fromString);
    }

    public Mono<Boolean> remove(UUID userId, UUID chatId) {
        String userKey = userId.toString();
        String expectedChatId = chatId.toString();

        return redisTemplate.<String, String>opsForHash()
                .get(KEY, userKey)
                .filter(expectedChatId::equals)
                .flatMap(_ -> redisTemplate.opsForHash().remove(KEY, userKey))
                .map(removed -> removed > 0)
                .defaultIfEmpty(false);
    }
}
