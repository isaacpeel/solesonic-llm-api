package com.solesonic.service.interest;

import java.util.EnumMap;
import java.util.Map;

/**
 * What one discovery run did, in counts only. Nothing here may ever carry memory content, a topic or
 * a query: it exists so a run can be logged and judged without any of them.
 */
public final class DiscoveryRun {
    public enum DropReason {
        NO_MEMORY,
        BLANK_FIELD,
        BLOCKED_TOPIC,
        LOW_CONFIDENCE
    }

    private int offered;
    private int nothing;
    private int discarded;
    private int failed;
    private final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);

    void offered() {
        offered++;
    }

    void nothing() {
        nothing++;
    }

    void discarded() {
        discarded++;
    }

    void failed() {
        failed++;
    }

    void dropped(DropReason dropReason) {
        drops.merge(dropReason, 1, Integer::sum);
    }

    public int offeredCount() {
        return offered;
    }

    public int nothingCount() {
        return nothing;
    }

    public int discardedCount() {
        return discarded;
    }

    public int failedCount() {
        return failed;
    }

    public Map<DropReason, Integer> drops() {
        return Map.copyOf(drops);
    }

    @Override
    public String toString() {
        return "offered=" + offered
                + " nothing=" + nothing
                + " discarded=" + discarded
                + " failed=" + failed
                + " drops=" + drops;
    }
}
