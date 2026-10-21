package com.solesonic.model.interest;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * An agent the user turned on by accepting an offer. It keeps its own label and query rather than
 * the memories that suggested it, which is why it outlives them.
 */
@Entity
public class InterestAgent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    private UUID offerId;

    @Enumerated(EnumType.STRING)
    private AgentType agentType;

    private String topicKey;

    private String label;

    private String query;

    private UUID chatGroupId;

    @Enumerated(EnumType.STRING)
    private AgentCadence cadence;

    @Enumerated(EnumType.STRING)
    private AgentStatus status;

    private ZonedDateTime nextRunAt;

    private ZonedDateTime lastRunAt;

    private ZonedDateTime lastUpdateAt;

    private ZonedDateTime lastFailureAt;

    private int failures;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public void setOfferId(UUID offerId) {
        this.offerId = offerId;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public void setAgentType(AgentType agentType) {
        this.agentType = agentType;
    }

    public String getTopicKey() {
        return topicKey;
    }

    public void setTopicKey(String topicKey) {
        this.topicKey = topicKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public UUID getChatGroupId() {
        return chatGroupId;
    }

    public void setChatGroupId(UUID chatGroupId) {
        this.chatGroupId = chatGroupId;
    }

    public AgentCadence getCadence() {
        return cadence;
    }

    public void setCadence(AgentCadence cadence) {
        this.cadence = cadence;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStatus status) {
        this.status = status;
    }

    public ZonedDateTime getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(ZonedDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public ZonedDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(ZonedDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public ZonedDateTime getLastUpdateAt() {
        return lastUpdateAt;
    }

    public void setLastUpdateAt(ZonedDateTime lastUpdateAt) {
        this.lastUpdateAt = lastUpdateAt;
    }

    public ZonedDateTime getLastFailureAt() {
        return lastFailureAt;
    }

    public void setLastFailureAt(ZonedDateTime lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    public int getFailures() {
        return failures;
    }

    public void setFailures(int failures) {
        this.failures = failures;
    }

    public ZonedDateTime getCreated() {
        return created;
    }

    public void setCreated(ZonedDateTime created) {
        this.created = created;
    }

    public ZonedDateTime getUpdated() {
        return updated;
    }

    public void setUpdated(ZonedDateTime updated) {
        this.updated = updated;
    }
}
