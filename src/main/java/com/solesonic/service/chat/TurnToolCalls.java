package com.solesonic.service.chat;

import com.solesonic.model.chat.ToolCallSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tool calls a chat's running turn has finished, kept until the turn completes and saves them on
 * its assistant message. In memory, like {@link PendingMessageMetadata}: the tools and the turn run in
 * the same JVM, and the turn's cleanup clears whatever was not saved, so nothing reaches the next turn.
 */
@Component
public class TurnToolCalls {
    private final Map<UUID, List<ToolCallSummary>> finished = new ConcurrentHashMap<>();

    public void record(UUID chatId, ToolCallSummary toolCall) {
        finished.compute(chatId, (_, toolCalls) -> {
            List<ToolCallSummary> updated = toolCalls == null ? new ArrayList<>() : toolCalls;
            updated.add(toolCall);

            return updated;
        });
    }

    public List<ToolCallSummary> take(UUID chatId) {
        List<ToolCallSummary> toolCalls = finished.remove(chatId);

        return toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public void clear(UUID chatId) {
        finished.remove(chatId);
    }
}
