package com.solesonic.model.push;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One browser's push subscription.
 * <p>
 * Never serialized to a client: {@link PushSubscriptionSummary} is the only response shape. The
 * endpoint and keys are what let anyone push to that browser, so they never leave the server.
 */
@Entity
public class PushSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    private String endpoint;

    private String p256dh;

    private String auth;

    private ZonedDateTime expirationTime;

    private String label;

    private String userAgent;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    private ZonedDateTime lastDelivered;

    private int failureCount;

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

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public void setP256dh(String p256dh) {
        this.p256dh = p256dh;
    }

    public String getAuth() {
        return auth;
    }

    public void setAuth(String auth) {
        this.auth = auth;
    }

    public ZonedDateTime getExpirationTime() {
        return expirationTime;
    }

    public void setExpirationTime(ZonedDateTime expirationTime) {
        this.expirationTime = expirationTime;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
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

    public ZonedDateTime getLastDelivered() {
        return lastDelivered;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }
}
