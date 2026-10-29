package com.solesonic.model.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Several users working with one A2A agent in one {@code SHARED} chat. The turn and the open question
 * are what the agent reported at the end of the last run, kept here so a participant who was away
 * learns whose turn it is from a read rather than from a stream they were not on.
 */
@Entity
@Table(name = "shared_session")
public class SharedSession {
    @Id
    private UUID id;

    private UUID chatId;

    private String agentName;

    @Enumerated(EnumType.STRING)
    private SessionStatus status;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private SessionTurn turnState;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private SessionPrompt pendingPrompt;

    private UUID lastRunId;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getChatId() {
        return chatId;
    }

    public void setChatId(UUID chatId) {
        this.chatId = chatId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public SessionTurn getTurnState() {
        return turnState;
    }

    public void setTurnState(SessionTurn turnState) {
        this.turnState = turnState;
    }

    public SessionPrompt getPendingPrompt() {
        return pendingPrompt;
    }

    public void setPendingPrompt(SessionPrompt pendingPrompt) {
        this.pendingPrompt = pendingPrompt;
    }

    public UUID getLastRunId() {
        return lastRunId;
    }

    public void setLastRunId(UUID lastRunId) {
        this.lastRunId = lastRunId;
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
