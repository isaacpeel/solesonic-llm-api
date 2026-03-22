package com.solesonic.redis.service;

import com.solesonic.redis.model.RedisChatEvent;
import com.solesonic.redis.publisher.ChatStreamPublisher;
import com.solesonic.redis.subscriber.ChatStreamSubscriber;
import com.solesonic.service.redis.RedisStreamingChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.data.domain.Range.unbounded;
import static org.springframework.data.redis.connection.Limit.limit;

@Service
public class RedisStreamService {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamService.class);
    private static final String STREAM_KEY_TEMPLATE = "chat:%s:%s";

    private final ChatStreamPublisher chatStreamPublisher;
    private final ChatStreamSubscriber chatStreamSubscriber;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final JsonMapper jsonMapper;
    private final ReactiveStringRedisTemplate redisTemplate;

    public RedisStreamService(ChatStreamPublisher chatStreamPublisher,
                              ChatStreamSubscriber chatStreamSubscriber,
                              JsonMapper jsonMapper,
                              ReactiveStringRedisTemplate redisTemplate) {
        this.chatStreamPublisher = chatStreamPublisher;
        this.chatStreamSubscriber = chatStreamSubscriber;
        this.jsonMapper = jsonMapper;
        this.redisTemplate = redisTemplate;
    }

    public Mono<RecordId> publish(UUID chatId, UUID userId, String type) {
        RedisStreamingChatService.ChunkPayload emptyPayload = new RedisStreamingChatService.ChunkPayload("");

        return publish(chatId, userId, type, emptyPayload);
    }

    public Mono<RecordId> publish(UUID chatId, UUID userId, String type, Object payload) {
        String serializePayload = serializePayload(payload);
        String streamKey = buildStreamKey(chatId, userId);

        RedisChatEvent streamEvent = RedisChatEvent.builder()
                .chatId(chatId)
                .userId(userId)
                .type(type)
                .payload(serializePayload)
                .correlationId(chatId.toString())
                .internalSequence(sequenceCounter.incrementAndGet())
                .build();

        return chatStreamPublisher.publish(streamKey, streamEvent);
    }

    /**
     * This is the offset where to resume the given stream from redis.
     *
     * @param chatId Chat ID to build the stream key from
     * @param userId User ID to build the stream key from
     * @return The offset of the most recent stream for the stream key
     */
    public Mono<String> getLatestOffset(UUID chatId, UUID userId) {
        String streamKey = buildStreamKey(chatId, userId);

        return redisTemplate.opsForStream()
                .reverseRange(streamKey, unbounded(), limit().count(1))
                .next()
                .map(record -> record.getId().getValue())
                .defaultIfEmpty("0")
                .onErrorResume(_ -> {
                    log.debug("Stream {} does not exist yet, starting from 0", streamKey);
                    //If all fails, start the strea from the beginning
                    return Mono.just("0");
                });
    }

    public Flux<ServerSentEvent<?>> subscribe(UUID chatId, UUID userId, String lastEventId) {
        String streamKey = buildStreamKey(chatId, userId);

        log.info("Subscribing to Redis stream {} for chat {}", streamKey, chatId);

        return chatStreamSubscriber.subscribe(streamKey, lastEventId);
    }

    public String buildStreamKey(UUID chatId, UUID userId) {
        return STREAM_KEY_TEMPLATE.formatted(chatId, userId);
    }

    private String serializePayload(Object payload) {
        if (payload == null) {
            return "";
        }

        if (payload instanceof String stringPayload) {
            return stringPayload;
        }

        return jsonMapper.writeValueAsString(payload);
    }

    @SuppressWarnings("unused")
    public Mono<Boolean> deleteStream(UUID chatId, UUID userId) {
        String streamKey = buildStreamKey(chatId, userId);
        log.debug("Deleting Redis stream {} before new exchange", streamKey);
        return redisTemplate.delete(streamKey).map(count -> count > 0);
    }
}
