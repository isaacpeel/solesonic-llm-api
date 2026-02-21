package com.solesonic.redis.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisChatEventTest {

    @Test
    void builderCreatesEventWithAllFields() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedisChatEvent event = RedisChatEvent.builder()
                .eventId("test-event-id")
                .type("chunk")
                .chatId(chatId)
                .userId(userId)
                .payload("{\"content\":\"hello\"}")
                .timestamp(1700000000000L)
                .correlationId("corr-123")
                .internalSequence(42)
                .build();

        assertThat(event.getEventId()).isEqualTo("test-event-id");
        assertThat(event.getType()).isEqualTo("chunk");
        assertThat(event.getChatId()).isEqualTo(chatId);
        assertThat(event.getUserId()).isEqualTo(userId);
        assertThat(event.getPayload()).isEqualTo("{\"content\":\"hello\"}");
        assertThat(event.getTimestamp()).isEqualTo(1700000000000L);
        assertThat(event.getCorrelationId()).isEqualTo("corr-123");
        assertThat(event.getInternalSequence()).isEqualTo(42);
    }

    @Test
    void builderGeneratesDefaultEventIdAndTimestamp() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedisChatEvent event = RedisChatEvent.builder()
                .type("init")
                .chatId(chatId)
                .userId(userId)
                .build();

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getTimestamp()).isGreaterThan(0);
    }

    @Test
    void toMapSerializesAllFields() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedisChatEvent event = RedisChatEvent.builder()
                .eventId("evt-1")
                .type("done")
                .chatId(chatId)
                .userId(userId)
                .payload("{\"status\":\"complete\"}")
                .timestamp(1700000000000L)
                .correlationId("corr-456")
                .internalSequence(10)
                .build();

        Map<String, String> eventMap = event.toMap();

        assertThat(eventMap).hasSize(10);
        assertThat(eventMap.get("eventId")).isEqualTo("evt-1");
        assertThat(eventMap.get("type")).isEqualTo("done");
        assertThat(eventMap.get("chatId")).isEqualTo(chatId.toString());
        assertThat(eventMap.get("userId")).isEqualTo(userId.toString());
        assertThat(eventMap.get("principal")).isEqualTo("user@test.com");
        assertThat(eventMap.get("authorities")).isEqualTo("ROLE_USER");
        assertThat(eventMap.get("payload")).isEqualTo("{\"status\":\"complete\"}");
        assertThat(eventMap.get("timestamp")).isEqualTo("1700000000000");
        assertThat(eventMap.get("correlationId")).isEqualTo("corr-456");
        assertThat(eventMap.get("internalSequence")).isEqualTo("10");
    }

    @Test
    void toMapHandlesNullOptionalFields() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedisChatEvent event = RedisChatEvent.builder()
                .type("chunk")
                .chatId(chatId)
                .userId(userId)
                .build();

        Map<String, String> eventMap = event.toMap();

        assertThat(eventMap.get("principal")).isEqualTo("");
        assertThat(eventMap.get("authorities")).isEqualTo("");
        assertThat(eventMap.get("payload")).isEqualTo("");
        assertThat(eventMap.get("correlationId")).isEqualTo("");
    }

    @Test
    void fromMapDeserializesCorrectly() {
        UUID chatId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        RedisChatEvent original = RedisChatEvent.builder()
                .eventId("evt-roundtrip")
                .type("chunk")
                .chatId(chatId)
                .userId(userId)
                .payload("{\"key\":\"value\"}")
                .timestamp(1700000000000L)
                .correlationId("corr-rt")
                .internalSequence(99)
                .build();

        Map<String, String> eventMap = original.toMap();

        Map<Object, Object> objectMap = new java.util.HashMap<>();
        eventMap.forEach(objectMap::put);

        RedisChatEvent deserialized = RedisChatEvent.fromMap(objectMap);

        assertThat(deserialized.getEventId()).isEqualTo(original.getEventId());
        assertThat(deserialized.getType()).isEqualTo(original.getType());
        assertThat(deserialized.getChatId()).isEqualTo(original.getChatId());
        assertThat(deserialized.getUserId()).isEqualTo(original.getUserId());
        assertThat(deserialized.getPayload()).isEqualTo(original.getPayload());
        assertThat(deserialized.getTimestamp()).isEqualTo(original.getTimestamp());
        assertThat(deserialized.getCorrelationId()).isEqualTo(original.getCorrelationId());
        assertThat(deserialized.getInternalSequence()).isEqualTo(original.getInternalSequence());
    }
}
