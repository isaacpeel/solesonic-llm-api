package com.solesonic.model.interest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A topic discovery thinks one user would want news about, waiting for them to say yes. Never
 * serialized to a client: {@link InterestOfferSummary} is the only response shape, and it leaves out
 * the query, the topic key and the memories the offer came from.
 */
@Entity
public class InterestOffer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private AgentType agentType;

    private String topicKey;

    private String label;

    private String query;

    private String rationale;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "uuid[]")
    private List<UUID> sourceMemoryIds;

    private double confidence;

    @Enumerated(EnumType.STRING)
    private OfferStatus status;

    private ZonedDateTime created;

    private ZonedDateTime expires;

    private ZonedDateTime decided;

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

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public List<UUID> getSourceMemoryIds() {
        return sourceMemoryIds;
    }

    public void setSourceMemoryIds(List<UUID> sourceMemoryIds) {
        this.sourceMemoryIds = sourceMemoryIds;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public void setStatus(OfferStatus status) {
        this.status = status;
    }

    public ZonedDateTime getCreated() {
        return created;
    }

    public void setCreated(ZonedDateTime created) {
        this.created = created;
    }

    public ZonedDateTime getExpires() {
        return expires;
    }

    public void setExpires(ZonedDateTime expires) {
        this.expires = expires;
    }

    public ZonedDateTime getDecided() {
        return decided;
    }

    public void setDecided(ZonedDateTime decided) {
        this.decided = decided;
    }
}
