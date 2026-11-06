package com.solesonic.service.memory;

import com.solesonic.service.memory.ValidatedReflection.DropReason;

import java.util.EnumMap;
import java.util.Map;

/**
 * What one reflection run did, in counts only. Nothing here may ever carry memory content or
 * transcript text: it exists so a run can be logged and judged without either.
 */
public final class ReflectionRun {
    private int windows;
    private int additions;
    private int supersessions;
    private int confirmations;
    private int discarded;
    private int failed;
    private final Map<DropReason, Integer> drops = new EnumMap<>(DropReason.class);

    void applied(ValidatedReflection reflection) {
        windows++;
        additions += reflection.additions().size();
        supersessions += reflection.supersessions().size();
        confirmations += reflection.confirmations().size();
        reflection.drops().forEach((reason, count) -> drops.merge(reason, count, Integer::sum));
    }

    void discarded() {
        discarded++;
    }

    void failed() {
        failed++;
    }

    public int additions() {
        return additions;
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
        return "windows=" + windows
                + " additions=" + additions
                + " supersessions=" + supersessions
                + " confirmations=" + confirmations
                + " discarded=" + discarded
                + " failed=" + failed
                + " drops=" + drops;
    }
}
