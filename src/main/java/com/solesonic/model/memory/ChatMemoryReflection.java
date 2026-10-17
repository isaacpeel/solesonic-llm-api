package com.solesonic.model.memory;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * How far reflection has read into one conversation. {@code reflectedThrough} is the timestamp of
 * the newest message already consumed, so a conversation that grows is picked up from there.
 */
@Entity
public class ChatMemoryReflection {
    @Id
    private UUID chatId;

    private UUID userId;

    private ZonedDateTime reflectedThrough;

    @Enumerated(EnumType.STRING)
    private ReflectionStatus status;

    private int attempts;

    private ZonedDateTime updated;

    public UUID getChatId() {
        return chatId;
    }

    public void setChatId(UUID chatId) {
        this.chatId = chatId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public ZonedDateTime getReflectedThrough() {
        return reflectedThrough;
    }

    public void setReflectedThrough(ZonedDateTime reflectedThrough) {
        this.reflectedThrough = reflectedThrough;
    }

    public ReflectionStatus getStatus() {
        return status;
    }

    public void setStatus(ReflectionStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public ZonedDateTime getUpdated() {
        return updated;
    }

    public void setUpdated(ZonedDateTime updated) {
        this.updated = updated;
    }
}
