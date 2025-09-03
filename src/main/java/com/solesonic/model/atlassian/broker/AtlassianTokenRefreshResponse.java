package com.solesonic.model.atlassian.broker;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.ZonedDateTime;

public record AtlassianTokenRefreshResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") int expiresIn,
        @JsonProperty("scope") String scope,
        ZonedDateTime created,
        ZonedDateTime updated,
        Integer rotationCounter) {

    public AtlassianTokenRefreshResponse(AtlassianTokenRefreshResponse atlassianTokenRefreshResponse,
                                         ZonedDateTime updated,
                                         Integer rotationCounter) {
        this(
                atlassianTokenRefreshResponse.accessToken(),
                atlassianTokenRefreshResponse.refreshToken(),
                atlassianTokenRefreshResponse.expiresIn(),
                atlassianTokenRefreshResponse.scope(),
                atlassianTokenRefreshResponse.created(),
                updated,
                rotationCounter
        );
    }

    public boolean hasNewRefreshToken(String oldRefreshToken) {
        return refreshToken != null && !refreshToken.equals(oldRefreshToken);
    }
}