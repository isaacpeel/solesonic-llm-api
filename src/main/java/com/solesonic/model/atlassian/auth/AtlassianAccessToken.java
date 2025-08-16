package com.solesonic.model.atlassian.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
public class AtlassianAccessToken {

    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID userId;

    @JsonProperty("access_token")
    @Column(columnDefinition = "TEXT")
    private String accessToken;

    @JsonProperty("refresh_token")
    @Column(columnDefinition = "TEXT")
    private String refreshToken;

    @Column(columnDefinition = "TEXT")
    private String scope;

    @JsonProperty("expires_in")
    private Integer expiresIn;

    private boolean administrator;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    public boolean isExpired() {
        if (expiresIn == null || created == null) {
            throw new IllegalStateException("Token must have both expiresIn and created fields initialized.");
        }

        // Calculate the expiration time with a 10-second buffer
        ZonedDateTime expirationTime = created.plusSeconds(expiresIn).minusSeconds(10);

        // Check if the current time is after the expiration time
        return !ZonedDateTime.now().isAfter(expirationTime);
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Integer getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(Integer expiresIn) {
        this.expiresIn = expiresIn;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
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

    @SuppressWarnings("unused")
    public boolean isAdministrator() {
        return administrator;
    }

    @SuppressWarnings("unused")
    public void setAdministrator(boolean administrator) {
        this.administrator = administrator;
    }
}
