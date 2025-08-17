package com.solesonic.model.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Formula;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
public class UserPreferences {
    @Id
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID userId;

    private ZonedDateTime created;

    private ZonedDateTime updated;

    private String model;

    private Double similarityThreshold;

    @Formula("(select count(1) > 0 from atlassian_access_token act where act.user_id = user_id)")
    private boolean atlassianAuthentication;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
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
    public boolean isAtlassianAuthentication() {
        return atlassianAuthentication;
    }

    @SuppressWarnings("unused")
    public void setAtlassianAuthentication(boolean atlassianAuthentication) {
        this.atlassianAuthentication = atlassianAuthentication;
    }

    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(Double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }
}
