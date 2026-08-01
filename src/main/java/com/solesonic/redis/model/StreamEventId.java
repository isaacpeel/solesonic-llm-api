package com.solesonic.redis.model;

import java.util.Optional;

/**
 * A Redis stream entry id — {@code <millisecondsSinceEpoch>-<sequence>} — parsed into its two
 * halves so resume cursors can be compared without string tricks.
 * <p>
 * These are the values handed to clients as the SSE {@code id:} field, and handed back on
 * {@code Last-Event-ID}. Comparing them lexicographically is wrong once the millisecond half
 * changes width, and comparing only the millisecond half loses every frame that shared a
 * millisecond with another — hence a pair, ordered most significant first.
 */
public record StreamEventId(long milliseconds, long sequence) implements Comparable<StreamEventId> {

    /**
     * The Redis sentinel meaning "from the start of the stream". Never the id of a real entry.
     */
    public static final String BEGINNING = "0";

    public static Optional<StreamEventId> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String trimmed = value.trim();
        int separatorIndex = trimmed.indexOf('-');

        try {
            if (separatorIndex < 0) {
                return Optional.of(new StreamEventId(Long.parseLong(trimmed), 0L));
            }

            long milliseconds = Long.parseLong(trimmed.substring(0, separatorIndex));
            long sequence = Long.parseLong(trimmed.substring(separatorIndex + 1));

            return Optional.of(new StreamEventId(milliseconds, sequence));
        } catch (NumberFormatException numberFormatException) {
            return Optional.empty();
        }
    }

    public static boolean isBeginning(String value) {
        return parse(value)
                .filter(streamEventId -> streamEventId.milliseconds() == 0L && streamEventId.sequence() == 0L)
                .isPresent();
    }

    @Override
    public int compareTo(StreamEventId other) {
        int millisecondComparison = Long.compare(milliseconds, other.milliseconds);

        if (millisecondComparison != 0) {
            return millisecondComparison;
        }

        return Long.compare(sequence, other.sequence);
    }

    @Override
    public String toString() {
        return milliseconds + "-" + sequence;
    }
}
