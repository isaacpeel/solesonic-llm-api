package com.solesonic.config.interest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Fails startup on interest-agent configuration that would misbehave quietly rather than loudly.
 */
@Configuration
public class InterestConfig {
    static final int MAX_EXTRACT_URLS = 5;
    static final int MAX_SEARCH_RESULTS = 10;
    static final int MIN_ITEM_RETENTION_DAYS = 7;

    @Bean
    public InterestSettings interestSettings(@Value("${solesonic.interest.discovery.batch-size}") int discoveryBatchSize,
                                             @Value("${solesonic.interest.discovery.max-attempts}") int discoveryMaxAttempts,
                                             @Value("${solesonic.interest.discovery.min-confidence}") double minConfidence,
                                             @Value("${solesonic.interest.offer.cooldown-days}") long offerCooldownDays,
                                             @Value("${solesonic.interest.offer.ttl-days}") long offerTtlDays,
                                             @Value("${solesonic.interest.offer.decline-cooldown-days}") long declineCooldownDays,
                                             @Value("${solesonic.interest.max-agents-per-user}") int maxAgentsPerUser,
                                             @Value("${solesonic.interest.news.batch-size}") int newsBatchSize,
                                             @Value("${solesonic.interest.news.max-results}") int maxResults,
                                             @Value("${solesonic.interest.news.extract-top}") int extractTop,
                                             @Value("${solesonic.interest.news.max-article-characters}") int maxArticleCharacters,
                                             @Value("${solesonic.interest.news.max-source-characters}") int maxSourceCharacters,
                                             @Value("${solesonic.interest.news.max-sources-per-update}") int maxSourcesPerUpdate,
                                             @Value("${solesonic.interest.news.max-summary-characters}") int maxSummaryCharacters,
                                             @Value("${solesonic.interest.news.delivery-hour}") int deliveryHour,
                                             @Value("${solesonic.interest.news.jitter-minutes}") long jitterMinutes,
                                             @Value("${solesonic.interest.news.run-lease-minutes}") long runLeaseMinutes,
                                             @Value("${solesonic.interest.news.max-failures}") int maxFailures,
                                             @Value("${solesonic.interest.news.item-retention-days}") long itemRetentionDays,
                                             @Value("${solesonic.interest.news.manual-run-cooldown-minutes}") long manualRunCooldownMinutes) {

        requirePositive(discoveryBatchSize, "INTEREST_DISCOVERY_BATCH_SIZE");
        requirePositive(discoveryMaxAttempts, "INTEREST_DISCOVERY_MAX_ATTEMPTS");
        requirePositive(maxAgentsPerUser, "INTEREST_MAX_AGENTS_PER_USER");
        requirePositive(newsBatchSize, "INTEREST_NEWS_BATCH_SIZE");
        requirePositive(maxSourcesPerUpdate, "INTEREST_NEWS_MAX_SOURCES_PER_UPDATE");
        requirePositive(maxArticleCharacters, "INTEREST_NEWS_MAX_ARTICLE_CHARACTERS");
        requirePositive(maxSourceCharacters, "INTEREST_NEWS_MAX_SOURCE_CHARACTERS");
        requirePositive(maxSummaryCharacters, "INTEREST_NEWS_MAX_SUMMARY_CHARACTERS");
        requirePositive(maxFailures, "INTEREST_NEWS_MAX_FAILURES");

        if (minConfidence < 0 || minConfidence > 1) {
            throw new IllegalStateException("INTEREST_DISCOVERY_MIN_CONFIDENCE must be between 0 and 1");
        }

        if (offerCooldownDays < 0 || offerTtlDays < 1 || declineCooldownDays < 0) {
            throw new IllegalStateException("INTEREST_OFFER_COOLDOWN_DAYS and INTEREST_DECLINE_COOLDOWN_DAYS must not be negative, "
                    + "and INTEREST_OFFER_TTL_DAYS must be at least 1");
        }

        if (maxResults < 1 || maxResults > MAX_SEARCH_RESULTS) {
            throw new IllegalStateException("INTEREST_NEWS_MAX_RESULTS must be between 1 and " + MAX_SEARCH_RESULTS);
        }

        if (extractTop < 0 || extractTop > MAX_EXTRACT_URLS) {
            throw new IllegalStateException("INTEREST_NEWS_EXTRACT_TOP must be between 0 and " + MAX_EXTRACT_URLS
                    + ", the extract tool's limit");
        }

        if (deliveryHour < 0 || deliveryHour > 23) {
            throw new IllegalStateException("INTEREST_NEWS_DELIVERY_HOUR must be between 0 and 23");
        }

        if (jitterMinutes < 0 || runLeaseMinutes < 1 || manualRunCooldownMinutes < 0) {
            throw new IllegalStateException("INTEREST_NEWS_JITTER_MINUTES and INTEREST_NEWS_MANUAL_RUN_COOLDOWN_MINUTES must not be "
                    + "negative, and INTEREST_NEWS_RUN_LEASE_MINUTES must be at least 1");
        }

        if (itemRetentionDays < MIN_ITEM_RETENTION_DAYS) {
            throw new IllegalStateException("INTEREST_NEWS_ITEM_RETENTION_DAYS must be at least " + MIN_ITEM_RETENTION_DAYS
                    + ": shorter than the widest search window would show week-old articles again");
        }

        return new InterestSettings(discoveryBatchSize,
                discoveryMaxAttempts,
                minConfidence,
                Duration.ofDays(offerCooldownDays),
                Duration.ofDays(offerTtlDays),
                Duration.ofDays(declineCooldownDays),
                maxAgentsPerUser,
                newsBatchSize,
                maxResults,
                extractTop,
                maxArticleCharacters,
                maxSourceCharacters,
                maxSourcesPerUpdate,
                maxSummaryCharacters,
                deliveryHour,
                Duration.ofMinutes(jitterMinutes),
                Duration.ofMinutes(runLeaseMinutes),
                maxFailures,
                Duration.ofDays(itemRetentionDays),
                Duration.ofMinutes(manualRunCooldownMinutes));
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalStateException(name + " must be at least 1");
        }
    }
}
