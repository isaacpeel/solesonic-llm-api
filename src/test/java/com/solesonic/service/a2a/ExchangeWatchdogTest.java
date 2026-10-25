package com.solesonic.service.a2a;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeWatchdogTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(300);

    private final List<Throwable> timeouts = new ArrayList<>();

    private VirtualTimeScheduler virtualTime;
    private ExchangeWatchdog watchdog;

    @BeforeEach
    void setUp() {
        virtualTime = VirtualTimeScheduler.getOrSet();
        watchdog = new ExchangeWatchdog(TIMEOUT, timeouts::add);
    }

    @AfterEach
    void tearDown() {
        VirtualTimeScheduler.reset();
    }

    @Test
    void anArmedExchangeTimesOutWhenTheAgentGoesQuiet() {
        watchdog.arm();

        virtualTime.advanceTimeBy(TIMEOUT.minusSeconds(1));
        assertThat(timeouts).isEmpty();

        virtualTime.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(timeouts).singleElement().isInstanceOf(TimeoutException.class);
    }

    @Test
    void everyEventRestartsTheClock() {
        watchdog.arm();

        virtualTime.advanceTimeBy(TIMEOUT.minusSeconds(1));
        watchdog.touch();
        virtualTime.advanceTimeBy(TIMEOUT.minusSeconds(1));

        assertThat(timeouts).isEmpty();
    }

    @Test
    void nothingTimesOutWhileAPromptWaits() {
        watchdog.arm();
        watchdog.pause();

        virtualTime.advanceTimeBy(TIMEOUT.multipliedBy(3));

        assertThat(timeouts).isEmpty();
    }

    /**
     * The agent may still send a trailing event after it asked its question; that must not bring the
     * timeout back while the user is deciding.
     */
    @Test
    void anEventDuringAPromptDoesNotRestartTheClock() {
        watchdog.arm();
        watchdog.pause();
        watchdog.touch();

        virtualTime.advanceTimeBy(TIMEOUT.multipliedBy(3));

        assertThat(timeouts).isEmpty();
    }

    @Test
    void theAnswerRestartsTheClock() {
        watchdog.arm();
        watchdog.pause();
        virtualTime.advanceTimeBy(TIMEOUT.multipliedBy(3));

        watchdog.arm();
        virtualTime.advanceTimeBy(TIMEOUT);

        assertThat(timeouts).hasSize(1);
    }

    @Test
    void aStoppedWatchdogNeverFiresAgain() {
        watchdog.arm();
        watchdog.stop();
        watchdog.arm();
        watchdog.touch();

        virtualTime.advanceTimeBy(TIMEOUT.multipliedBy(3));

        assertThat(timeouts).isEmpty();
    }
}
