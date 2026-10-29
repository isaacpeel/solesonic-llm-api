package com.solesonic.model.session;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "session_invite")
public class SessionInvite {
    @Id
    private UUID id;

    private UUID inviterUserId;

    private UUID inviteeUserId;

    private String agentName;

    private String openingMessage;

    @Enumerated(EnumType.STRING)
    private SessionInviteStatus status;

    private UUID sessionId;

    private ZonedDateTime created;

    private ZonedDateTime expires;

    private ZonedDateTime decided;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getInviterUserId() {
        return inviterUserId;
    }

    public void setInviterUserId(UUID inviterUserId) {
        this.inviterUserId = inviterUserId;
    }

    public UUID getInviteeUserId() {
        return inviteeUserId;
    }

    public void setInviteeUserId(UUID inviteeUserId) {
        this.inviteeUserId = inviteeUserId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getOpeningMessage() {
        return openingMessage;
    }

    public void setOpeningMessage(String openingMessage) {
        this.openingMessage = openingMessage;
    }

    public SessionInviteStatus getStatus() {
        return status;
    }

    public void setStatus(SessionInviteStatus status) {
        this.status = status;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
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
