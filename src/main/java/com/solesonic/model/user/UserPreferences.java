package com.solesonic.model.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.atlassian.auth.AtlassianAccessTokenConverter;
import com.solesonic.model.google.auth.GoogleAccessToken;
import com.solesonic.model.google.auth.GoogleAccessTokenConverter;
import jakarta.persistence.*;

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

    private Double chatSimilarityThreshold;

    private Double userSimilarityThreshold;

    private Double globalSimilarityThreshold;

    @Transient
    private boolean atlassianAuthentication;

    /**
     * {@code @JsonIgnore} because this entity is serialized straight to the client by
     * {@code UserController}. Without it, {@code GET /users/{userId}/preferences} answers with the
     * user's Atlassian access <em>and refresh</em> token in the response body, where it lands in
     * browser memory, any intermediary cache and every HAR file support ever asks for. The client
     * learns whether Atlassian is connected from {@link #atlassianAuthentication}.
     */
    @JsonIgnore
    @Convert(converter = AtlassianAccessTokenConverter.class)
    @Column(name = "atlassian_access_token", columnDefinition = "bytea")
    private AtlassianAccessToken atlassianAccessToken;

    @Transient
    private boolean googleAuthentication;

    /**
     * {@code @JsonIgnore} for the same reason as {@link #atlassianAccessToken}: the client learns
     * whether Google is connected from {@link #googleAuthentication} and has no reason to ever
     * receive the tokens themselves.
     */
    @JsonIgnore
    @Convert(converter = GoogleAccessTokenConverter.class)
    @Column(name = "google_access_token", columnDefinition = "bytea")
    private GoogleAccessToken googleAccessToken;

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

    public Double getChatSimilarityThreshold() {
        return chatSimilarityThreshold;
    }

    public void setChatSimilarityThreshold(Double chatSimilarityThreshold) {
        this.chatSimilarityThreshold = chatSimilarityThreshold;
    }

    public Double getUserSimilarityThreshold() {
        return userSimilarityThreshold;
    }

    public void setUserSimilarityThreshold(Double userSimilarityThreshold) {
        this.userSimilarityThreshold = userSimilarityThreshold;
    }

    public Double getGlobalSimilarityThreshold() {
        return globalSimilarityThreshold;
    }

    public void setGlobalSimilarityThreshold(Double globalSimilarityThreshold) {
        this.globalSimilarityThreshold = globalSimilarityThreshold;
    }

    public AtlassianAccessToken getAtlassianAccessToken() {
        return atlassianAccessToken;
    }

    public void setAtlassianAccessToken(AtlassianAccessToken atlassianAccessToken) {
        this.atlassianAccessToken = atlassianAccessToken;
    }

    @SuppressWarnings("unused")
    public boolean isGoogleAuthentication() {
        return googleAuthentication;
    }

    public void setGoogleAuthentication(boolean googleAuthentication) {
        this.googleAuthentication = googleAuthentication;
    }

    public GoogleAccessToken getGoogleAccessToken() {
        return googleAccessToken;
    }

    public void setGoogleAccessToken(GoogleAccessToken googleAccessToken) {
        this.googleAccessToken = googleAccessToken;
    }
}
