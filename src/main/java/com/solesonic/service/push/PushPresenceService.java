package com.solesonic.service.push;

import com.solesonic.config.push.PushSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Whether anyone is reading a chat's stream right now, kept server-side because Chrome's
 * {@code userVisibleOnly} contract makes a service worker show every push it receives: a push
 * cannot be silently dropped on arrival.
 * <p>
 * One sorted set per chat, {@code push:presence:{userId}:{chatId}}, whose members are reader ids
 * scored by expiry. A reader refreshes itself on its own heartbeat rather than on keepalive
 * comments, which go out only while a stream is silent.
 * <p>
 * Nothing here may affect a stream: every Redis failure is logged and swallowed, and a failed check
 * counts as unwatched, since an extra push beats a missing one.
 */
@Service
public class PushPresenceService {
    private static final Logger log = LoggerFactory.getLogger(PushPresenceService.class);
    private static final String KEY_TEMPLATE = "push:presence:%s:%s";
    private static final Duration STOPPED_READER_GRACE = Duration.ofSeconds(10);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final PushSettings pushSettings;
    private final Clock clock;

    public PushPresenceService(ReactiveStringRedisTemplate redisTemplate, PushSettings pushSettings, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.pushSettings = pushSettings;
        this.clock = clock;
    }

    public <T> Flux<T> track(UUID userId, UUID chatId, Flux<T> stream) {
        if (userId == null) {
            return stream;
        }

        return Flux.using(() -> startReading(userId, chatId), _ -> stream, this::stopReading);
    }

    private Reader startReading(UUID userId, UUID chatId) {
        String key = key(userId, chatId);
        String readerId = UUID.randomUUID().toString();

        Disposable heartbeat = register(key, readerId, chatId)
                .thenMany(Flux.interval(pushSettings.presenceHeartbeat()))
                .concatMap(_ -> register(key, readerId, chatId))
                .subscribe();

        return new Reader(chatId, key, readerId, heartbeat);
    }

    /**
     * A reader is left to lapse after a short grace rather than removed. A turn's own reader ends the
     * moment it reads the terminal frame, which is also the moment the turn's push asks whether
     * anyone is watching; removing it outright would push to the person who just watched the reply.
     */
    private void stopReading(Reader reader) {
        reader.heartbeat().dispose();

        double expiresAt = clock.millis() + STOPPED_READER_GRACE.toMillis();

        Mono.defer(() -> redisTemplate.opsForZSet().add(reader.key(), reader.readerId(), expiresAt))
                .onErrorResume(error -> {
                    log.warn("Could not clear push presence for chat {}: {}", reader.chatId(), error.getClass().getSimpleName());

                    return Mono.empty();
                })
                .subscribe();
    }

    public Mono<Boolean> isWatching(UUID userId, UUID chatId) {
        String key = key(userId, chatId);
        Range<Double> expired = Range.leftUnbounded(Range.Bound.inclusive((double) clock.millis()));

        return Mono.defer(() -> redisTemplate.opsForZSet().removeRangeByScore(key, expired))
                .then(Mono.defer(() -> redisTemplate.opsForZSet().size(key)))
                .map(readers -> readers > 0)
                .defaultIfEmpty(false)
                .onErrorResume(error -> {
                    log.warn("Could not read push presence for chat {}, treating it as unwatched: {}",
                            chatId, error.getClass().getSimpleName());

                    return Mono.just(false);
                });
    }

    private Mono<Void> register(String key, String readerId, UUID chatId) {
        double expiresAt = clock.millis() + pushSettings.presenceTtl().toMillis();

        return Mono.defer(() -> redisTemplate.opsForZSet().add(key, readerId, expiresAt))
                .then(Mono.defer(() -> redisTemplate.expire(key, pushSettings.presenceTtl())))
                .then()
                .onErrorResume(error -> {
                    log.warn("Could not record push presence for chat {}: {}", chatId, error.getClass().getSimpleName());

                    return Mono.empty();
                });
    }

    private static String key(UUID userId, UUID chatId) {
        return KEY_TEMPLATE.formatted(userId, chatId);
    }

    private record Reader(UUID chatId, String key, String readerId, Disposable heartbeat) {
    }
}
