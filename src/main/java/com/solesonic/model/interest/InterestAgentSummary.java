package com.solesonic.model.interest;

import java.time.ZonedDateTime;
import java.util.UUID;

public record InterestAgentSummary(UUID id,
                                   AgentType agentType,
                                   String label,
                                   String query,
                                   AgentCadence cadence,
                                   AgentStatus status,
                                   UUID chatGroupId,
                                   ZonedDateTime nextRunAt,
                                   ZonedDateTime lastRunAt,
                                   ZonedDateTime lastUpdateAt,
                                   ZonedDateTime lastFailureAt,
                                   int failures,
                                   ZonedDateTime created) {

    public static InterestAgentSummary of(InterestAgent interestAgent) {
        return new InterestAgentSummary(interestAgent.getId(),
                interestAgent.getAgentType(),
                interestAgent.getLabel(),
                interestAgent.getQuery(),
                interestAgent.getCadence(),
                interestAgent.getStatus(),
                interestAgent.getChatGroupId(),
                interestAgent.getNextRunAt(),
                interestAgent.getLastRunAt(),
                interestAgent.getLastUpdateAt(),
                interestAgent.getLastFailureAt(),
                interestAgent.getFailures(),
                interestAgent.getCreated());
    }
}
