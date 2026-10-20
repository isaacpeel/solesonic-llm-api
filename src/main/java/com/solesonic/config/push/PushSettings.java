package com.solesonic.config.push;

import java.time.Duration;
import java.util.Set;

/**
 * Push configuration, validated once at startup by {@link PushConfig}. Hosts are lower-cased.
 */
public record PushSettings(boolean enabled,
                           Set<String> allowedEndpointHosts,
                           int maxAttempts,
                           int maxFailures,
                           Duration elicitationReminderDelay,
                           Duration elicitationTimeout,
                           Duration presenceTtl,
                           Duration presenceHeartbeat) {
}
