package com.solesonic.model.agentupdate;

import java.util.UUID;

/**
 * @param duplicate the key was delivered before: this is that first chat, and nothing was pushed
 */
public record AgentUpdateResponse(UUID chatId, UUID updateId, boolean duplicate) {
}
