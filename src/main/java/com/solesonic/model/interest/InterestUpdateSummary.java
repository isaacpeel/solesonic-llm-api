package com.solesonic.model.interest;

import java.time.ZonedDateTime;
import java.util.UUID;

public record InterestUpdateSummary(UUID id,
                                    UUID agentId,
                                    String label,
                                    UUID chatId,
                                    int itemCount,
                                    ZonedDateTime created) {

    public static InterestUpdateSummary of(InterestUpdate interestUpdate) {
        return new InterestUpdateSummary(interestUpdate.getId(),
                interestUpdate.getAgentId(),
                interestUpdate.getLabel(),
                interestUpdate.getChatId(),
                interestUpdate.getItemCount(),
                interestUpdate.getCreated());
    }
}
