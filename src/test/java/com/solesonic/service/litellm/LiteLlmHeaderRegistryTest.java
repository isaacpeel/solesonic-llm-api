package com.solesonic.service.litellm;

import com.solesonic.model.chat.LiteLlmCallMetadata;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteLlmHeaderRegistryTest {

    private static final Instant NOW = Instant.parse("2026-09-11T13:14:41Z");

    private static LiteLlmCallMetadata call(String callId, String modelName) {
        return new LiteLlmCallMetadata(callId, modelName, "http://izzy-bot-spark:8585/v1", 0, 0);
    }

    @Test
    void keepsATurnsCallsInTheOrderTheyWereMade() {
        LiteLlmHeaderRegistry registry = new LiteLlmHeaderRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
        UUID correlationId = UUID.randomUUID();

        registry.record(correlationId, call("call-1", "qwen3.5-9b"));
        registry.record(correlationId, call("call-2", "qwen3.5-30b"));

        List<LiteLlmCallMetadata> taken = registry.take(correlationId);

        assertEquals(2, taken.size());
        assertEquals("call-1", taken.getFirst().callId());
        assertEquals("qwen3.5-30b", taken.getLast().modelName());
    }

    /**
     * Two turns in flight at once must not see each other's calls — the registry is shared by every
     * concurrent chat on the instance.
     */
    @Test
    void keepsConcurrentTurnsApart() {
        LiteLlmHeaderRegistry registry = new LiteLlmHeaderRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
        UUID firstTurn = UUID.randomUUID();
        UUID secondTurn = UUID.randomUUID();

        registry.record(firstTurn, call("call-1", "qwen3.5-9b"));
        registry.record(secondTurn, call("call-2", "qwen3.5-30b"));

        assertEquals(List.of("call-1"), registry.take(firstTurn).stream().map(LiteLlmCallMetadata::callId).toList());
        assertEquals(List.of("call-2"), registry.take(secondTurn).stream().map(LiteLlmCallMetadata::callId).toList());
    }

    /**
     * Reading removes, so a turn whose write is retried cannot silently attach the previous
     * attempt's calls a second time.
     */
    @Test
    void takingATurnTwiceYieldsNothingTheSecondTime() {
        LiteLlmHeaderRegistry registry = new LiteLlmHeaderRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
        UUID correlationId = UUID.randomUUID();

        registry.record(correlationId, call("call-1", "qwen3.5-9b"));

        assertEquals(1, registry.take(correlationId).size());
        assertTrue(registry.take(correlationId).isEmpty());
    }

    @Test
    void aTurnThatNeverCollectedIsSweptOnceItsRetentionHasPassed() {
        MutableClock clock = new MutableClock(NOW);
        LiteLlmHeaderRegistry registry = new LiteLlmHeaderRegistry(clock);
        UUID abandonedTurn = UUID.randomUUID();

        registry.record(abandonedTurn, call("call-1", "qwen3.5-9b"));

        clock.advance(LiteLlmHeaderRegistry.RETENTION.plusMinutes(1));

        //Any later write is what sweeps; the registry has no timer of its own.
        registry.record(UUID.randomUUID(), call("call-2", "qwen3.5-9b"));

        assertTrue(registry.take(abandonedTurn).isEmpty());
    }

    @Test
    void nothingWasRecordedForAnUnknownTurn() {
        LiteLlmHeaderRegistry registry = new LiteLlmHeaderRegistry(Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(registry.take(UUID.randomUUID()).isEmpty());
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
