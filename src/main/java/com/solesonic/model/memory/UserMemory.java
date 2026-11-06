package com.solesonic.model.memory;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One durable thing known about one user.
 * <p>
 * Never serialized to a client: {@link UserMemorySummary} is the only response shape. Content is
 * only ever written together with the evidence it came from, or by the user directly, which is what
 * keeps every inferred memory attributable to the conversations its evidence names.
 */
@Entity
public class UserMemory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private MemoryKind kind;

    @Enumerated(EnumType.STRING)
    private MemoryOrigin origin;

    @Enumerated(EnumType.STRING)
    private MemoryStatus status;

    private String memoryKey;

    private String content;

    private double confidence;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    private ZonedDateTime lastConfirmed;

    private UUID supersedes;

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

    public MemoryKind getKind() {
        return kind;
    }

    public void setKind(MemoryKind kind) {
        this.kind = kind;
    }

    public MemoryOrigin getOrigin() {
        return origin;
    }

    public void setOrigin(MemoryOrigin origin) {
        this.origin = origin;
    }

    public MemoryStatus getStatus() {
        return status;
    }

    public void setStatus(MemoryStatus status) {
        this.status = status;
    }

    public String getMemoryKey() {
        return memoryKey;
    }

    public void setMemoryKey(String memoryKey) {
        this.memoryKey = memoryKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
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

    public ZonedDateTime getLastConfirmed() {
        return lastConfirmed;
    }

    public void setLastConfirmed(ZonedDateTime lastConfirmed) {
        this.lastConfirmed = lastConfirmed;
    }

    public UUID getSupersedes() {
        return supersedes;
    }

    public void setSupersedes(UUID supersedes) {
        this.supersedes = supersedes;
    }
}
