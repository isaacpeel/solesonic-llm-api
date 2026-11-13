package com.solesonic.model.chat;

import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What one turn learns about its own session writes, for the route to act on once the turn ends:
 * which session event holds the live user message, and whether the tool loop ended without a model
 * round. Created per turn, like {@link ResponseMetadataCapture}, and passed as an advisor param.
 * <p>
 * The event id is the one {@code SessionMemoryAdvisor} names on the request it forwards. Only the
 * first round's is kept: later rounds name their tool results.
 */
public final class SessionTurnRecord {
    private final AtomicReference<String> userEventId = new AtomicReference<>();
    private final AtomicBoolean endedWithoutModelRound = new AtomicBoolean();

    public void recordUserEventId(String eventId) {
        if (eventId != null && !eventId.isBlank()) {
            userEventId.compareAndSet(null, eventId);
        }
    }

    public Optional<String> userEventId() {
        return Optional.ofNullable(userEventId.get());
    }

    /**
     * A {@code returnDirect} tool or a breached tool-call limit ends the loop on the tool's own text,
     * which no model round answered, so the session holds a tool result and no assistant reply.
     */
    public void recordFinishReason(String finishReason) {
        if (ToolExecutionResult.FINISH_REASON.equals(finishReason)
                || ToolCallLimitExceededException.FINISH_REASON.equals(finishReason)) {
            endedWithoutModelRound.set(true);
        }
    }

    public boolean endedWithoutModelRound() {
        return endedWithoutModelRound.get();
    }
}
