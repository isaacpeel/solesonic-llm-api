package com.solesonic.model.chat;

import org.jspecify.annotations.Nullable;

/**
 * One tool call as the chat shows it while a turn runs: a {@code start} when the model calls the
 * tool, then an {@code end} with how it went and how long it took. Both carry the same
 * {@code toolCallId}, so the client can pair them.
 *
 * @param arguments  the call's input, compacted and cut short for display
 * @param status     {@code ok} or {@code error}, on {@code end} only
 * @param durationMs the call's wall time, on {@code end} only
 */
public record ToolActivityEvent(String toolCallId,
                                String name,
                                String phase,
                                @Nullable String arguments,
                                @Nullable String status,
                                @Nullable Long durationMs) {

    public static final String START = "start";
    public static final String END = "end";
    public static final String OK = "ok";
    public static final String ERROR = "error";

    public static ToolActivityEvent started(String toolCallId, String name, @Nullable String arguments) {
        return new ToolActivityEvent(toolCallId, name, START, arguments, null, null);
    }

    public static ToolActivityEvent ended(String toolCallId, String name, @Nullable String arguments, String status, long durationMs) {
        return new ToolActivityEvent(toolCallId, name, END, arguments, status, durationMs);
    }
}
