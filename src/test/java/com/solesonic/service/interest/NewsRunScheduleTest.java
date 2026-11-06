package com.solesonic.service.interest;

import com.solesonic.model.interest.AgentCadence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class NewsRunScheduleTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void aDailyAgentRunsTomorrowAtTheDeliveryHourInTheOwnersZone() {
        ZonedDateTime now = ZonedDateTime.parse("2026-10-21T15:00:00Z");

        ZonedDateTime next = NewsRunSchedule.next(now, AgentCadence.DAILY, NEW_YORK, 8, Duration.ZERO);

        assertThat(next.withZoneSameInstant(NEW_YORK).toLocalDateTime()).hasToString("2026-10-22T08:00");
    }

    @Test
    void aWeeklyAgentRunsSevenDaysOn() {
        ZonedDateTime now = ZonedDateTime.parse("2026-10-21T15:00:00Z");

        ZonedDateTime next = NewsRunSchedule.next(now, AgentCadence.WEEKLY, NEW_YORK, 8, Duration.ZERO);

        assertThat(next.withZoneSameInstant(NEW_YORK).toLocalDateTime()).hasToString("2026-10-28T08:00");
    }

    /**
     * Clocks go back on 1 November 2026 in New York: the briefing still arrives at 08:00 local, which
     * is an hour later in UTC than the day before.
     */
    @Test
    void staysOnTheLocalDeliveryHourAcrossTheEndOfDaylightSaving() {
        ZonedDateTime now = ZonedDateTime.parse("2026-10-31T13:00:00Z");

        ZonedDateTime next = NewsRunSchedule.next(now, AgentCadence.DAILY, NEW_YORK, 8, Duration.ZERO);

        assertThat(next.withZoneSameInstant(NEW_YORK).getHour()).isEqualTo(8);
        assertThat(next.toInstant()).hasToString("2026-11-01T13:00:00Z");
    }

    @Test
    void anHourSkippedBySpringForwardResolvesToTheHourAfter() {
        ZonedDateTime now = ZonedDateTime.parse("2026-03-07T12:00:00Z");

        ZonedDateTime next = NewsRunSchedule.next(now, AgentCadence.DAILY, NEW_YORK, 2, Duration.ZERO);

        assertThat(next.withZoneSameInstant(NEW_YORK).toLocalDateTime()).hasToString("2026-03-08T03:00");
    }

    @Test
    void addsTheJitter() {
        ZonedDateTime now = ZonedDateTime.parse("2026-10-21T15:00:00Z");

        ZonedDateTime next = NewsRunSchedule.next(now, AgentCadence.DAILY, NEW_YORK, 8, Duration.ofMinutes(17));

        assertThat(next.withZoneSameInstant(NEW_YORK).toLocalDateTime()).hasToString("2026-10-22T08:17");
    }

    @Test
    void backsOffExponentiallyCappedAtTheCadence() {
        ZonedDateTime now = ZonedDateTime.parse("2026-10-21T15:00:00Z");

        assertThat(NewsRunSchedule.backoff(now, AgentCadence.DAILY, 1)).isEqualTo(now.plusHours(2));
        assertThat(NewsRunSchedule.backoff(now, AgentCadence.DAILY, 3)).isEqualTo(now.plusHours(8));
        assertThat(NewsRunSchedule.backoff(now, AgentCadence.DAILY, 5)).isEqualTo(now.plusDays(1));
        assertThat(NewsRunSchedule.backoff(now, AgentCadence.WEEKLY, 5)).isEqualTo(now.plusHours(32));
        assertThat(NewsRunSchedule.backoff(now, AgentCadence.WEEKLY, 40)).isEqualTo(now.plusDays(7));
    }

    @Test
    void searchesTheWindowTheCadenceCovers() {
        ZonedDateTime now = ZonedDateTime.parse("2026-10-21T15:00:00Z");

        assertThat(NewsRunSchedule.timeRange(AgentCadence.DAILY, now.minusDays(1), now)).isEqualTo("day");
        assertThat(NewsRunSchedule.timeRange(AgentCadence.WEEKLY, now.minusDays(7), now)).isEqualTo("week");
        assertThat(NewsRunSchedule.timeRange(AgentCadence.DAILY, null, now)).isEqualTo("day");
    }

    /**
     * After a pause or a run of failures the gap's stories are still found.
     */
    @Test
    void widensTheWindowWhenTheLastSuccessfulRunIsFurtherBack() {
        ZonedDateTime now = ZonedDateTime.parse("2026-10-21T15:00:00Z");

        assertThat(NewsRunSchedule.timeRange(AgentCadence.DAILY, now.minusDays(3), now)).isEqualTo("week");
        assertThat(NewsRunSchedule.timeRange(AgentCadence.WEEKLY, now.minusDays(10), now)).isEqualTo("month");
    }
}
