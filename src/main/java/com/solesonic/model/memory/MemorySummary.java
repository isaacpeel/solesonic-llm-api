package com.solesonic.model.memory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A memory as an administrator sees it: {@link UserMemorySummary} plus what the owner's own panel
 * deliberately leaves out — whose it is, its key, and what it superseded.
 */
public record MemorySummary(UUID id,
                                 UUID userId,
                                 MemoryKind kind,
                                 MemoryOrigin origin,
                                 MemoryStatus status,
                                 String memoryKey,
                                 String content,
                                 double confidence,
                                 ZonedDateTime created,
                                 ZonedDateTime updated,
                                 ZonedDateTime lastConfirmed,
                                 UUID supersedes,
                                 List<UUID> chatIds) {

    public static MemorySummary of(UserMemory userMemory, List<UUID> chatIds) {
        return new MemorySummary(userMemory.getId(),
                userMemory.getUserId(),
                userMemory.getKind(),
                userMemory.getOrigin(),
                userMemory.getStatus(),
                userMemory.getMemoryKey(),
                userMemory.getContent(),
                userMemory.getConfidence(),
                userMemory.getCreated(),
                userMemory.getUpdated(),
                userMemory.getLastConfirmed(),
                userMemory.getSupersedes(),
                chatIds);
    }
}
