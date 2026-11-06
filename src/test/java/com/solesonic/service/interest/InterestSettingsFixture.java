package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;

import java.time.Duration;

public final class InterestSettingsFixture {
    public static final int MAX_AGENTS_PER_USER = 5;
    public static final int MAX_SOURCES_PER_UPDATE = 6;
    public static final int MAX_SUMMARY_CHARACTERS = 2500;
    public static final int DELIVERY_HOUR = 8;
    public static final int MAX_FAILURES = 5;
    public static final Duration MANUAL_RUN_COOLDOWN = Duration.ofMinutes(10);
    public static final Duration RUN_LEASE = Duration.ofMinutes(30);

    private InterestSettingsFixture() {
    }

    public static InterestSettings settings() {
        return settings(0, 20_000, Duration.ZERO);
    }

    public static InterestSettings settings(int extractTop, int maxSourceCharacters, Duration jitter) {
        return new InterestSettings(5,
                3,
                0.7,
                Duration.ofDays(7),
                Duration.ofDays(7),
                Duration.ofDays(90),
                MAX_AGENTS_PER_USER,
                10,
                10,
                extractTop,
                4000,
                maxSourceCharacters,
                MAX_SOURCES_PER_UPDATE,
                MAX_SUMMARY_CHARACTERS,
                DELIVERY_HOUR,
                jitter,
                RUN_LEASE,
                MAX_FAILURES,
                Duration.ofDays(30),
                MANUAL_RUN_COOLDOWN);
    }
}
