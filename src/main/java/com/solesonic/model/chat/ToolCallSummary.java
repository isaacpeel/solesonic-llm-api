package com.solesonic.model.chat;

import org.jspecify.annotations.Nullable;

/**
 * One finished tool call, as it is kept on the assistant message of the turn that made it, so the
 * chat can show its tool log again after a reload.
 */
public record ToolCallSummary(String toolCallId,
                              String name,
                              @Nullable String arguments,
                              String status,
                              long durationMs) {

    public static ToolCallSummary of(ToolActivityEvent ended) {
        return new ToolCallSummary(ended.toolCallId(), ended.name(), ended.arguments(), ended.status(),
                ended.durationMs() == null ? 0L : ended.durationMs());
    }
}
