package com.solesonic.redis.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class RedisChatEvent {
    public static final String EVENT_ID = "eventId";
    public static final String TYPE = "type";
    public static final String CHAT_ID = "chatId";
    public static final String USER_ID = "userId";
    public static final String PAYLOAD = "payload";
    public static final String TIMESTAMP = "timestamp";
    public static final String CORRELATION_ID = "correlationId";
    public static final String INTERNAL_SEQUENCE = "internalSequence";

    private final String eventId;
    private final String type;
    private final UUID chatId;
    private final UUID userId;
    private final String payload;
    private final long timestamp;
    private final String correlationId;
    private final long internalSequence;

    private RedisChatEvent(Builder builder) {
        this.eventId = builder.eventId;
        this.type = builder.type;
        this.chatId = builder.chatId;
        this.userId = builder.userId;
        this.payload = builder.payload;
        this.timestamp = builder.timestamp;
        this.correlationId = builder.correlationId;
        this.internalSequence = builder.internalSequence;
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public UUID getChatId() {
        return chatId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPayload() {
        return payload;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public long getInternalSequence() {
        return internalSequence;
    }

    public Map<String, Object> toMap() {
        return Map.of(
        EVENT_ID, eventId,
        TYPE, type,
        CHAT_ID, chatId,
        USER_ID, userId,
        PAYLOAD, payload,
        TIMESTAMP, timestamp,
        CORRELATION_ID, correlationId,
        INTERNAL_SEQUENCE, internalSequence);
    }

    public static RedisChatEvent fromMap(Map<Object, Object> map) {
        return builder()
                .eventId(stringValue(map, EVENT_ID))
                .type(stringValue(map, TYPE))
                .chatId(UUID.fromString(stringValue(map, CHAT_ID)))
                .userId(UUID.fromString(stringValue(map, USER_ID)))
                .payload(stringValue(map, PAYLOAD))
                .timestamp(Long.parseLong(stringValue(map, TIMESTAMP)))
                .correlationId(stringValue(map, CORRELATION_ID))
                .internalSequence(Long.parseLong(stringValue(map, INTERNAL_SEQUENCE)))
                .build();
    }

    private static String stringValue(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String eventId = UUID.randomUUID().toString();
        private String type;
        private UUID chatId;
        private UUID userId;
        private String payload;
        private long timestamp = Instant.now().toEpochMilli();
        private String correlationId;
        private long internalSequence;

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder chatId(UUID chatId) {
            this.chatId = chatId;
            return this;
        }

        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder internalSequence(long internalSequence) {
            this.internalSequence = internalSequence;
            return this;
        }

        public RedisChatEvent build() {
            return new RedisChatEvent(this);
        }
    }
}
