package com.solesonic.service.memory;

import com.solesonic.model.memory.UserMemory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * How much a memory is worth keeping in front of the model: its confidence, decayed by how long it
 * has gone without being confirmed. Injecting a memory never confirms it, so one the user never
 * brings up again sinks.
 */
final class MemoryRanking {
    private static final double HALF_LIFE_DAYS = 90.0;

    private MemoryRanking() {
    }

    static double score(UserMemory memory, ZonedDateTime now) {
        double ageDays = Math.max(0.0, Duration.between(memory.getLastConfirmed(), now).toMinutes() / (60.0 * 24.0));

        return memory.getConfidence() * Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
    }

    static Comparator<UserMemory> lowestFirst(ZonedDateTime now) {
        return Comparator.comparingDouble((UserMemory memory) -> score(memory, now));
    }

    static Comparator<UserMemory> highestFirst(ZonedDateTime now) {
        return lowestFirst(now).reversed();
    }

    /**
     * Relevance to the turn weighted by {@link #score}, so a close match the user has not confirmed in
     * a year can still rank below a slightly weaker match they brought up last week.
     *
     * @param similarityById each memory's similarity to the turn; every memory compared must be in it
     */
    static Comparator<UserMemory> mostRelevantFirst(Map<UUID, Double> similarityById, ZonedDateTime now) {
        return Comparator.comparingDouble((UserMemory memory) -> similarityById.get(memory.getId()) * score(memory, now))
                .reversed();
    }
}
