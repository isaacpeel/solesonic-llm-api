package com.solesonic.redis.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StreamEventIdTest {

    @Test
    void parsesBothHalves() {
        Optional<StreamEventId> streamEventId = StreamEventId.parse("1754062831251-7");

        assertThat(streamEventId).contains(new StreamEventId(1754062831251L, 7L));
    }

    @Test
    void parsesIdWithoutSequence() {
        assertThat(StreamEventId.parse("0")).contains(new StreamEventId(0L, 0L));
    }

    @Test
    void rejectsNonIds() {
        assertThat(StreamEventId.parse(null)).isEmpty();
        assertThat(StreamEventId.parse("  ")).isEmpty();
        assertThat(StreamEventId.parse("latest")).isEmpty();
        assertThat(StreamEventId.parse("1754062831251-")).isEmpty();
        assertThat(StreamEventId.parse("1754062831251-x")).isEmpty();
    }

    /**
     * The whole reason ids are compared as a pair. Frames emitted inside one millisecond differ
     * only in the sequence half, and a cursor that ignores it silently skips every frame but the
     * first of that millisecond.
     */
    @Test
    void ordersBySequenceWithinTheSameMillisecond() {
        StreamEventId earlier = StreamEventId.parse("1754062831251-0").orElseThrow();
        StreamEventId later = StreamEventId.parse("1754062831251-1").orElseThrow();

        assertThat(earlier).isLessThan(later);
    }

    @Test
    void ordersByMillisecondsFirst() {
        StreamEventId earlier = StreamEventId.parse("1754062831251-9").orElseThrow();
        StreamEventId later = StreamEventId.parse("1754062831252-0").orElseThrow();

        assertThat(earlier).isLessThan(later);
    }

    @Test
    void recognizesTheBeginningSentinel() {
        assertThat(StreamEventId.isBeginning(StreamEventId.BEGINNING)).isTrue();
        assertThat(StreamEventId.isBeginning("0-0")).isTrue();
        assertThat(StreamEventId.isBeginning("1754062831251-0")).isFalse();
        assertThat(StreamEventId.isBeginning("nonsense")).isFalse();
    }
}
