package com.solesonic.model.a2a;

import org.a2aproject.sdk.spec.AgentCard;

public record RegisteredAgent(AgentCard agentCard, A2AAgentSource source) {
}
