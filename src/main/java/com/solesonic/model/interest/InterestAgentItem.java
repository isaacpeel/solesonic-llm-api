package com.solesonic.model.interest;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A URL an agent has already considered, by hash only: dedupe needs nothing more, and it keeps
 * third-party text out of a table nobody reads.
 */
@SuppressWarnings("unused")
@Entity
@IdClass(InterestAgentItemId.class)
public class InterestAgentItem {
    @Id
    private UUID agentId;

    @Id
    private String urlHash;

    private UUID userId;

    private ZonedDateTime firstSeen;

    public InterestAgentItem() {
    }

    public InterestAgentItem(UUID agentId, UUID userId, String urlHash, ZonedDateTime firstSeen) {
        this.agentId = agentId;
        this.userId = userId;
        this.urlHash = urlHash;
        this.firstSeen = firstSeen;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getUrlHash() {
        return urlHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public ZonedDateTime getFirstSeen() {
        return firstSeen;
    }
}
