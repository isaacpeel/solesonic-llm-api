package com.solesonic.model.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;

import java.time.Duration;

/**
 * Token usage and timing for one assistant turn, carried on the {@code done} SSE event so a client
 * can render it once the stream finishes.
 * <p>
 * {@code promptTokens}/{@code completionTokens}/{@code totalTokens}/{@code tokensPerSecond} are
 * {@code null} whenever the turn never went through a {@link Usage}-reporting chat model call — an
 * A2A agent delegation has no token accounting to report. {@code timeToFirstTokenMillis} is
 * {@code null} only when the turn produced no chunk at all. {@code durationMillis} is wall-clock and
 * always present, since it and {@code timeToFirstTokenMillis} are measured around the turn rather
 * than read from model metadata — both share the same {@code turnStarted} anchor, so they stay
 * comparable across every route, not only the ones a chat model reports usage for.
 */
public record ResponseMetadata(
        @Nullable Integer promptTokens,
        @Nullable Integer completionTokens,
        @Nullable Integer totalTokens,
        @Nullable Double tokensPerSecond,
        @Nullable Long timeToFirstTokenMillis,
        long durationMillis) {

    public static ResponseMetadata of(@Nullable Usage usage, @Nullable Long timeToFirstTokenMillis, Duration duration) {
        long durationMillis = duration.toMillis();

        if (usage == null) {
            return new ResponseMetadata(null, null, null, null, timeToFirstTokenMillis, durationMillis);
        }

        Integer completionTokens = usage.getCompletionTokens();

        Double tokensPerSecond = durationMillis > 0
                ? completionTokens * 1000.0 / durationMillis
                : null;

        return new ResponseMetadata(
                usage.getPromptTokens(),
                completionTokens,
                usage.getTotalTokens(),
                tokensPerSecond,
                timeToFirstTokenMillis,
                durationMillis);
    }
}
