package com.solesonic.config.interest;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterestConfigTest {
    private final InterestConfig interestConfig = new InterestConfig();

    @Test
    void buildsTheSettingsFromValidConfiguration() {
        InterestSettings interestSettings = settings(10, 3, 8, 30, 0.7);

        assertThat(interestSettings.offerTimeToLive()).isEqualTo(Duration.ofDays(7));
        assertThat(interestSettings.itemRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(interestSettings.jitter()).isEqualTo(Duration.ofMinutes(30));
    }

    /**
     * Shorter than the widest search window, and an article a week old would be shown again.
     */
    @Test
    void refusesARetentionShorterThanAWeek() {
        assertThatThrownBy(() -> settings(10, 3, 8, 6, 0.7)).hasMessageContaining("INTEREST_NEWS_ITEM_RETENTION_DAYS");
    }

    @Test
    void refusesADeliveryHourOutsideTheDay() {
        assertThatThrownBy(() -> settings(10, 3, 24, 30, 0.7)).hasMessageContaining("INTEREST_NEWS_DELIVERY_HOUR");
        assertThatThrownBy(() -> settings(10, 3, -1, 30, 0.7)).hasMessageContaining("INTEREST_NEWS_DELIVERY_HOUR");
    }

    @Test
    void refusesMoreExtractionsThanTheToolTakes() {
        assertThatThrownBy(() -> settings(10, 6, 8, 30, 0.7)).hasMessageContaining("INTEREST_NEWS_EXTRACT_TOP");
    }

    @Test
    void refusesMoreResultsThanTheSearchToolReturns() {
        assertThatThrownBy(() -> settings(11, 3, 8, 30, 0.7)).hasMessageContaining("INTEREST_NEWS_MAX_RESULTS");
    }

    @Test
    void refusesAConfidenceOutsideZeroToOne() {
        assertThatThrownBy(() -> settings(10, 3, 8, 30, 1.5)).hasMessageContaining("INTEREST_DISCOVERY_MIN_CONFIDENCE");
    }

    private InterestSettings settings(int maxResults, int extractTop, int deliveryHour, long itemRetentionDays, double minConfidence) {
        return interestConfig.interestSettings(5, 3, minConfidence, 7, 7, 90, 5, 10, maxResults, extractTop, 4000, 20000, 6, 2500,
                deliveryHour, 30, 30, 5, itemRetentionDays, 10);
    }
}
