package com.solesonic.service.interest;

import com.solesonic.model.interest.AgentCadence;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When an agent runs next. A run settles onto the delivery hour in the owner's own zone, so a daily
 * briefing arrives at the same local time across a daylight-saving change, plus a jitter so every
 * user's agent does not search in the same minute.
 */
public final class NewsRunSchedule {
    private static final Duration GRACE = Duration.ofHours(1);

    private NewsRunSchedule() {
    }

    /**
     * The delivery hour on the next day for {@code DAILY}, seven days on for {@code WEEKLY}. An hour
     * that a clock change skips resolves to the hour after it.
     */
    public static ZonedDateTime next(ZonedDateTime now, AgentCadence cadence, ZoneId zone, int deliveryHour, Duration jitter) {
        LocalDate today = now.withZoneSameInstant(zone).toLocalDate();
        LocalDate day = today.plusDays(cadenceLength(cadence).toDays());

        return ZonedDateTime.of(day, LocalTime.of(deliveryHour, 0), zone).plus(jitter);
    }

    /**
     * A whole number of minutes from zero to {@code maxJitter} inclusive.
     */
    public static Duration jitter(Duration maxJitter) {
        return Duration.ofMinutes(ThreadLocalRandom.current().nextLong(maxJitter.toMinutes() + 1));
    }

    /**
     * {@code 2^failures} hours from now, never longer than the cadence itself.
     */
    public static ZonedDateTime backoff(ZonedDateTime now, AgentCadence cadence, int failures) {
        Duration cap = cadenceLength(cadence);
        Duration delay = failures >= 30 ? cap : Duration.ofHours(1L << failures);

        return now.plus(delay.compareTo(cap) > 0 ? cap : delay);
    }

    /**
     * The window the cadence covers, widened one step when the last successful run is further back than
     * that — after a pause or a run of failures — so the stories from the gap are still found. A first
     * run searches the cadence's own window.
     */
    public static String timeRange(AgentCadence cadence, ZonedDateTime lastRunAt, ZonedDateTime now) {
        boolean behind = lastRunAt != null && lastRunAt.plus(cadenceLength(cadence)).plus(GRACE).isBefore(now);

        return switch (cadence) {
            case DAILY -> behind ? "week" : "day";
            case WEEKLY -> behind ? "month" : "week";
        };
    }

    private static Duration cadenceLength(AgentCadence cadence) {
        return switch (cadence) {
            case DAILY -> Duration.ofDays(1);
            case WEEKLY -> Duration.ofDays(7);
        };
    }
}
