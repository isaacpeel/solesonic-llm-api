package com.solesonic.service.chat;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata a route has for the assistant message it is about to stream, handed to that message's
 * {@code TEXT_MESSAGE_START}. In memory, because the route and the stream that publishes the start
 * frame run in the same JVM, in the same turn; the turn's cleanup clears whatever was never used, so
 * it cannot reach the next turn.
 */
@Component
public class PendingMessageMetadata {
    private final Map<UUID, Map<String, Object>> pending = new ConcurrentHashMap<>();

    public void put(UUID chatId, String key, Object value) {
        pending.compute(chatId, (_, metadata) -> {
            Map<String, Object> updated = metadata == null ? new LinkedHashMap<>() : metadata;
            updated.put(key, value);

            return updated;
        });
    }

    public Map<String, Object> take(UUID chatId) {
        Map<String, Object> metadata = pending.remove(chatId);

        return metadata == null ? Map.of() : metadata;
    }

    public void clear(UUID chatId) {
        pending.remove(chatId);
    }
}
