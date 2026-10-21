package com.solesonic.model.interest;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * How far discovery has read one user's memories. {@code scannedThrough} is the newest
 * {@code user_memory.updated} already considered, so discovery wakes only for a user reflection has
 * just taught something.
 */
@Entity
public class InterestScan {
    @Id
    private UUID userId;

    private ZonedDateTime scannedThrough;

    private ZonedDateTime lastOffered;

    @Enumerated(EnumType.STRING)
    private ScanStatus status;

    private int attempts;

    private ZonedDateTime updated;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public ZonedDateTime getScannedThrough() {
        return scannedThrough;
    }

    public void setScannedThrough(ZonedDateTime scannedThrough) {
        this.scannedThrough = scannedThrough;
    }

    public ZonedDateTime getLastOffered() {
        return lastOffered;
    }

    public void setLastOffered(ZonedDateTime lastOffered) {
        this.lastOffered = lastOffered;
    }

    public ScanStatus getStatus() {
        return status;
    }

    public void setStatus(ScanStatus status) {
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
