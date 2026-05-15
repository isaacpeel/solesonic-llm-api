package com.solesonic.service.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "solesonic.a2a", name = "enabled", havingValue = "true")
public class A2AStickyAgentService {

    private static final Logger log = LoggerFactory.getLogger(A2AStickyAgentService.class);
    private static final String STICKY_KEY_PREFIX = "chat:a2a:active-agent:";
    private static final Duration STICKY_TTL = Duration.ofHours(24);

    private final ReactiveStringRedisTemplate redisTemplate;

    public A2AStickyAgentService(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Void> activate(UUID chatId, String agentName) {
        log.debug("Activating sticky A2A agent '{}' for chat {}", agentName, chatId);

        return redisTemplate.opsForValue()
                .set(key(chatId), agentName, STICKY_TTL)
                .then();
    }

    public Mono<Optional<String>> getActiveAgent(UUID chatId) {
        return redisTemplate.opsForValue()
                .get(key(chatId))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    public Mono<Void> deactivate(UUID chatId) {
        log.debug("Deactivating sticky A2A agent for chat {}", chatId);

        return redisTemplate.delete(key(chatId)).then();
    }

    private String key(UUID chatId) {
        return STICKY_KEY_PREFIX + chatId;
    }
}
