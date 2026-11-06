package com.solesonic.model.memory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The only shape a memory takes on the wire. {@code chatIds} names the conversations its evidence
 * came from, so the panel can show where something was learned.
 */
public record UserMemorySummary(UUID id,
                                MemoryKind kind,
                                MemoryOrigin origin,
                                MemoryStatus status,
                                String content,
                                double confidence,
                                ZonedDateTime created,
                                ZonedDateTime updated,
                                ZonedDateTime lastConfirmed,
                                List<UUID> chatIds) {

    public static UserMemorySummary of(UserMemory userMemory, List<UUID> chatIds) {
        return new UserMemorySummary(userMemory.getId(),
                userMemory.getKind(),
                userMemory.getOrigin(),
                userMemory.getStatus(),
                userMemory.getContent(),
                userMemory.getConfidence(),
                userMemory.getCreated(),
                userMemory.getUpdated(),
                userMemory.getLastConfirmed(),
                chatIds);
    }
}
