package com.solesonic.model.interest;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * What a client is told about an offer. The query, the topic key and the memories it came from are
 * deliberately absent: the user sees what would be watched and why, nothing about how it was found.
 */
public record InterestOfferSummary(UUID id,
                                   AgentType agentType,
                                   String label,
                                   String rationale,
                                   OfferStatus status,
                                   ZonedDateTime created,
                                   ZonedDateTime expires) {

    public static InterestOfferSummary of(InterestOffer interestOffer) {
        return new InterestOfferSummary(interestOffer.getId(),
                interestOffer.getAgentType(),
                interestOffer.getLabel(),
                interestOffer.getRationale(),
                interestOffer.getStatus(),
                interestOffer.getCreated(),
                interestOffer.getExpires());
    }
}
