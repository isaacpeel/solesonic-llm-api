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

import java.util.Map;

@Service
public class ChatStreamPublisher {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamPublisher.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final long maxStreamLength;

    public ChatStreamPublisher(ReactiveStringRedisTemplate redisTemplate,
                               @Value("${redis.stream.max-length:1000}") long maxStreamLength) {
        this.redisTemplate = redisTemplate;
        this.maxStreamLength = maxStreamLength;
    }

    public Mono<RecordId> publish(String streamKey, RedisChatEvent event) {
        Map<String, String> eventMap = event.toMap();

        return redisTemplate.opsForStream()
                .add(StreamRecords.newRecord().in(streamKey).ofMap(eventMap))
                .doOnNext(recordId -> log.debug("Published event {} to stream {} with record id {}", event.getType(), streamKey, recordId.getValue()))
                .flatMap(recordId -> trimStream(streamKey).thenReturn(recordId))
                .doOnError(error -> log.error("Failed to publish event to stream {}: {}", streamKey, error.getMessage()));
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
