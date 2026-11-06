package com.solesonic.model.interest;

/**
 * A missing body or cadence means {@link AgentCadence#DAILY}.
 */
public record InterestAcceptRequest(AgentCadence cadence) {
}
