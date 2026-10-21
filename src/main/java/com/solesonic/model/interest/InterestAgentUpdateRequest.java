package com.solesonic.model.interest;

/**
 * A partial update: an absent field is left unchanged.
 */
public record InterestAgentUpdateRequest(String label, String query, AgentCadence cadence, AgentStatus status) {
}
