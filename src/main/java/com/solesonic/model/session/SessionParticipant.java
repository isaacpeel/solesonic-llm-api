package com.solesonic.model.session;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One seat in a shared session. {@code name} is the name the agent knows the participant by, assigned
 * once by {@code ParticipantNames} and the only identity ever sent to it.
 */
@Entity
@Table(name = "session_participant")
public class SessionParticipant {
    @Id
    private UUID id;

    private UUID sessionId;

    private UUID userId;

    private int seat;

    private String name;

    private ZonedDateTime joined;

    private ZonedDateTime leftAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public int getSeat() {
        return seat;
    }

    public void setSeat(int seat) {
        this.seat = seat;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ZonedDateTime getJoined() {
        return joined;
    }

    public void setJoined(ZonedDateTime joined) {
        this.joined = joined;
    }

    public ZonedDateTime getLeftAt() {
        return leftAt;
    }

    public void setLeftAt(ZonedDateTime leftAt) {
        this.leftAt = leftAt;
    }

    public boolean hasLeft() {
        return leftAt != null;
    }
}
