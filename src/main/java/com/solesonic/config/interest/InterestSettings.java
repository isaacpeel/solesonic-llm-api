package com.solesonic.config.interest;

import java.time.Duration;

/**
 * Interest-agent configuration, validated once at startup by {@link InterestConfig}. The two
 * {@code enabled} switches and the crons are read by the tasks themselves.
 */
public record InterestSettings(int discoveryBatchSize,
                               int discoveryMaxAttempts,
                               double minConfidence,
                               Duration offerCooldown,
                               Duration offerTimeToLive,
                               Duration declineCooldown,
                               int maxAgentsPerUser,
                               int newsBatchSize,
                               int maxResults,
                               int extractTop,
                               int maxArticleCharacters,
                               int maxSourceCharacters,
                               int maxSourcesPerUpdate,
                               int maxSummaryCharacters,
                               int deliveryHour,
                               Duration jitter,
                               Duration runLease,
                               int maxFailures,
                               Duration itemRetention,
                               Duration manualRunCooldown) {
}
