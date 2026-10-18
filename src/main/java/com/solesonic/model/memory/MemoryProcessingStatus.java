package com.solesonic.model.memory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Every background memory process across all users, with the settings it runs under, and the same
 * broken down per user.
 */
public record MemoryProcessingStatus(ZonedDateTime asOf,
                                     Reflection reflection,
                                     Embedding embedding,
                                     Injection injection,
                                     Map<MemoryStatus, Long> memoriesByStatus,
                                     List<UserMemoryOverview> users) {

    /**
     * {@code awaitingReflection} counts conversations with messages reflection has not read yet,
     * whatever their status — a completed one that carried on is waiting again. {@code unseededChats}
     * have messages but no reflection row; the next run gives them one.
     */
    public record Reflection(boolean enabled,
                             String model,
                             String cron,
                             int batchSize,
                             int maxAttempts,
                             long idleMinutes,
                             Map<ReflectionStatus, Long> chatsByStatus,
                             long awaitingReflection,
                             long unseededChats,
                             long completedLastHour,
                             ZonedDateTime lastCompleted) {
    }

    /**
     * {@code stale} counts live memories with no vector of {@code model} for their current content,
     * which relevance retrieval cannot find until the embedding task catches up.
     */
    public record Embedding(String model, String cron, int batchSize, long embedded, long stale) {
    }

    public record Injection(boolean enabled, MemoryRetrievalMode retrievalMode) {
    }
}
