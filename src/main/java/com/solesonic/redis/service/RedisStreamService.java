package com.solesonic.redis.service;

import com.solesonic.redis.model.RedisChatEvent;
import com.solesonic.redis.publisher.ChatStreamPublisher;
import com.solesonic.redis.subscriber.ChatStreamSubscriber;
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

@Service
public class RedisStreamService {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamService.class);
    private static final String STREAM_KEY_PREFIX = "chat";

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

    public Mono<RecordId> publish(UUID chatId, UUID userId, String type, Object payload) {
        String serializePayload = serializePayload(payload);
        String streamKey = buildStreamKey(chatId, userId);

        RedisChatEvent event = RedisChatEvent.builder()
                .chatId(chatId)
                .userId(userId)
                .type(type)
                .payload(serializePayload)
                .correlationId(chatId.toString())
                .internalSequence(sequenceCounter.incrementAndGet())
                .build();

        return chatStreamPublisher.publish(streamKey, event);
    }

    public Flux<ServerSentEvent<?>> subscribe(UUID chatId, UUID userId, String lastEventId) {
        String streamKey = buildStreamKey(chatId, userId);

        log.info("Subscribing to Redis stream {} for chat {}", streamKey, chatId);

        return chatStreamSubscriber.subscribe(streamKey, lastEventId);
    }

    public String buildStreamKey(UUID chatId, UUID userId) {
        return STREAM_KEY_PREFIX + ":" + chatId + ":" + userId;
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

    public Mono<Boolean> deleteStream(UUID chatId, UUID userId) {
        String streamKey = buildStreamKey(chatId, userId);
        log.debug("Deleting Redis stream {} before new exchange", streamKey);
        return redisTemplate.delete(streamKey).map(count -> count > 0);
    }
}
