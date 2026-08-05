package com.solesonic.redis.publisher;

import com.solesonic.redis.model.RedisChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Service
public class ChatStreamPublisher {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamPublisher.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final long maxStreamLength;
    private final Duration retention;

    public ChatStreamPublisher(ReactiveStringRedisTemplate redisTemplate,
                               @Value("${redis.stream.max-length:1000}") long maxStreamLength,
                               @Value("${redis.stream.retention-seconds:900}") long retentionSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxStreamLength = maxStreamLength;
        this.retention = Duration.ofSeconds(retentionSeconds);
    }

    public Mono<RecordId> publish(String streamKey, RedisChatEvent event) {
        Map<String, Object> eventMap = event.toMap();

        return redisTemplate.opsForStream()
                .add(StreamRecords.newRecord().in(streamKey).ofMap(eventMap))
                .doOnNext(recordId -> log.debug("Published event {} to stream {} with record id {}", event.getType(), streamKey, recordId.getValue()))
                .flatMap(recordId -> trimStream(streamKey).thenReturn(recordId))
                .flatMap(recordId -> extendRetention(streamKey).thenReturn(recordId))
                .doOnError(error -> log.error("Failed to publish event to stream {}: {}", streamKey, error.getMessage()));
    }

    /**
     * Slides the key's expiry forward on every frame, so a stream outlives its turn by the full
     * retention window and then goes away.
     * <p>
     * This window is what a client backgrounded mid-turn resumes into. Without it the streams were
     * immortal — trimmed to a frame count but never expired — so Redis grew by one key per chat
     * forever. It is also what makes an expired resume answerable with {@code 410} instead of a
     * subscription that waits for frames that will never arrive.
     */
    private Mono<Boolean> extendRetention(String streamKey) {
        return redisTemplate.expire(streamKey, retention)
                .onErrorResume(error -> {
                    log.warn("Failed to set retention on stream {}: {}", streamKey, error.getMessage());

                    return Mono.just(false);
                });
    }

    private Mono<Long> trimStream(String streamKey) {
        return redisTemplate.opsForStream()
                .trim(streamKey, maxStreamLength)
                .doOnNext(trimmed -> {
                    if (trimmed > 0) {
                        log.debug("Trimmed {} entries from stream {}", trimmed, streamKey);
                    }
                })
                .onErrorResume(error -> {
                    log.warn("Failed to trim stream {}: {}", streamKey, error.getMessage());

                    return Mono.just(0L);
                });
    }
}
