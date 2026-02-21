package com.solesonic.redis.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RedisChatEvent {

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

    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("eventId", eventId);
        map.put("type", type);
        map.put("chatId", chatId.toString());
        map.put("userId", userId.toString());
        map.put("payload", payload != null ? payload : "");
        map.put("timestamp", String.valueOf(timestamp));
        map.put("correlationId", correlationId != null ? correlationId : "");
        map.put("internalSequence", String.valueOf(internalSequence));
        return map;
    }

    public static RedisChatEvent fromMap(Map<Object, Object> map) {
        return builder()
                .eventId(stringValue(map, "eventId"))
                .type(stringValue(map, "type"))
                .chatId(UUID.fromString(stringValue(map, "chatId")))
                .userId(UUID.fromString(stringValue(map, "userId")))
                .payload(stringValue(map, "payload"))
                .timestamp(Long.parseLong(stringValue(map, "timestamp")))
                .correlationId(stringValue(map, "correlationId"))
                .internalSequence(Long.parseLong(stringValue(map, "internalSequence")))
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
