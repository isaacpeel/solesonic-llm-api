package com.solesonic.model.agentupdate;

import java.util.UUID;

/**
 * What an agent sends to {@code POST /agent-updates}. Every field but {@code groupName} and
 * {@code pinAgent} is required; {@code groupName} defaults to the agent's group (e.g. "Jobs").
 *
 * @param pinAgent attach the new chat to the agent (the sticky key), so the user's reply reaches it
 */
public record AgentUpdateRequest(UUID userId,
                                 String agent,
                                 String title,
                                 String markdown,
                                 String idempotencyKey,
                                 String groupName,
                                 Boolean pinAgent) {
}
