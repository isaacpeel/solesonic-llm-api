package com.solesonic.model.push;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Which events a user wants pushed. Absent means all are on.
 */
@Entity
public class PushPreference {
    @Id
    private UUID userId;

    private boolean turnComplete;

    private boolean elicitation;

    private boolean interestOffers;

    private boolean interestUpdates;

    private boolean sessionInvites;

    private boolean sessionTurns;

    private ZonedDateTime updated;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isTurnComplete() {
        return turnComplete;
    }

    public void setTurnComplete(boolean turnComplete) {
        this.turnComplete = turnComplete;
    }

    public boolean isElicitation() {
        return elicitation;
    }

    public void setElicitation(boolean elicitation) {
        this.elicitation = elicitation;
    }

    public boolean isInterestOffers() {
        return interestOffers;
    }

    public void setInterestOffers(boolean interestOffers) {
        this.interestOffers = interestOffers;
    }

    public boolean isInterestUpdates() {
        return interestUpdates;
    }

    public void setInterestUpdates(boolean interestUpdates) {
        this.interestUpdates = interestUpdates;
    }

    public boolean isSessionInvites() {
        return sessionInvites;
    }

    public void setSessionInvites(boolean sessionInvites) {
        this.sessionInvites = sessionInvites;
    }

    public boolean isSessionTurns() {
        return sessionTurns;
    }

    public void setSessionTurns(boolean sessionTurns) {
        this.sessionTurns = sessionTurns;
    }

    public ZonedDateTime getUpdated() {
        return updated;
    }

    public void setUpdated(ZonedDateTime updated) {
        this.updated = updated;
    }
}
