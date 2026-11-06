package com.solesonic.model.session;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The question a shared session's last run ended on: an MCP form elicitation the agent raised, saved
 * instead of parked so the participant it is for can answer whenever they get to it. This is the
 * stored form; {@code taskId}, the A2A task the answer continues, never leaves the server, which is
 * why clients get {@link SessionPromptView} instead.
 */
public record SessionPrompt(UUID elicitationId,
                            String taskId,
                            String message,
                            Map<String, Object> requestedSchema,
                            Map<String, Object> meta,
                            List<UUID> forUserIds) {

    public SessionPromptView view() {
        return new SessionPromptView(elicitationId, message, requestedSchema, meta, forUserIds);
    }
}
