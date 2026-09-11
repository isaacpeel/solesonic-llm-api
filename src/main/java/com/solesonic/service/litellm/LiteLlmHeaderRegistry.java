package com.solesonic.service.litellm;

import com.solesonic.model.chat.LiteLlmCallMetadata;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the {@code x-litellm-*} headers of each HTTP call until the turn that made them comes to
 * collect them.
 * <p>
 * A hand-off is needed because nothing carries the two together. A streamed turn is issued through
 * Spring AI's <em>async</em> OpenAI client ({@code OpenAiChatModel.internalStream} calls
 * {@code openAiClientAsync}), so the OkHttp interceptor that sees the headers runs on the SDK's
 * dispatcher thread while the subscriber that records the turn runs on another — no
 * {@code ThreadLocal} and no Reactor context spans the two. The correlation id travels in the
 * request body instead, and is read back off it by the interceptor.
 * <p>
 * Entries expire, and that is load-bearing rather than tidy: a cancelled or failed turn never
 * collects its own, so without {@link #RETENTION} every such turn would leak one entry for the life
 * of the process. {@link #MAX_CORRELATIONS} is the second bound, for a burst that outruns expiry.
 */
@Service
public class LiteLlmHeaderRegistry {

    static final Duration RETENTION = Duration.ofMinutes(10);
    static final int MAX_CORRELATIONS = 2048;

    private final Map<UUID, CapturedCalls> capturedCalls = new ConcurrentHashMap<>();
    private final Clock clock;

    public LiteLlmHeaderRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * Appends one call's headers. A tool-calling turn makes several HTTP calls under one correlation
     * id, so these accumulate in the order they were issued rather than replacing each other.
     */
    public void record(UUID correlationId, LiteLlmCallMetadata liteLlmCallMetadata) {
        evict();

        capturedCalls
                .computeIfAbsent(correlationId, _ -> new CapturedCalls(Instant.now(clock)))
                .add(liteLlmCallMetadata);
    }

    /**
     * One turn's calls in the order they were made, removed as they are read. Removing on read is
     * what keeps a completed turn from relying on expiry, leaving the TTL to cover only the turns
     * that never finish.
     */
    public List<LiteLlmCallMetadata> take(UUID correlationId) {
        CapturedCalls captured = capturedCalls.remove(correlationId);

        if (captured == null) {
            return List.of();
        }

        return captured.snapshot();
    }

    private void evict() {
        Instant expiredBefore = Instant.now(clock).minus(RETENTION);

        capturedCalls.values().removeIf(captured -> captured.capturedAt().isBefore(expiredBefore));

        if (capturedCalls.size() <= MAX_CORRELATIONS) {
            return;
        }

        capturedCalls.entrySet()
                .stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().capturedAt()))
                .limit(capturedCalls.size() - (long) MAX_CORRELATIONS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(capturedCalls::remove);
    }

    /**
     * Synchronised rather than a concurrent list: the writes are one interceptor thread at a time,
     * but {@link #take} can run while a later round trip is still recording, and a half-copied list
     * would drop a call silently.
     */
    private static final class CapturedCalls {

        private final List<LiteLlmCallMetadata> calls = new ArrayList<>();
        private final Instant capturedAt;

        private CapturedCalls(Instant capturedAt) {
            this.capturedAt = capturedAt;
        }

        private synchronized void add(LiteLlmCallMetadata liteLlmCallMetadata) {
            calls.add(liteLlmCallMetadata);
        }

        private synchronized List<LiteLlmCallMetadata> snapshot() {
            return List.copyOf(calls);
        }

        private Instant capturedAt() {
            return capturedAt;
        }
    }
}
