package com.solesonic.model.atlassian.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.ZonedDateTime;
import java.util.UUID;

public record AtlassianAccessToken(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        UUID userId,

        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty(value = "token_type")
        String tokenType,
        String scope,

        @JsonProperty(value = "expires_in")
        Integer expiresIn,

        boolean administrator,

        @JsonProperty(defaultValue = "0")
        ZonedDateTime created,
        ZonedDateTime updated,
        String error,

        @JsonProperty("error_description")
        String errorDescription
) {

    public boolean isExpired() {
        if (expiresIn == null || created == null) {
            return true;
        }

        ZonedDateTime expirationTime = created.plusSeconds(expiresIn).minusSeconds(10);
        return ZonedDateTime.now().isAfter(expirationTime);
    }

    public boolean hasNewRefreshToken(String oldRefreshToken) {
        return refreshToken != null && !refreshToken.equals(oldRefreshToken);
    }

    @SuppressWarnings("unused")
    public boolean isAdministrator() {
        return administrator;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder from(AtlassianAccessToken atlassianAccessToken) {
        return new Builder()
                .userId(atlassianAccessToken.userId)
                .accessToken(atlassianAccessToken.accessToken)
                .refreshToken(atlassianAccessToken.refreshToken)
                .tokenType(atlassianAccessToken.tokenType)
                .scope(atlassianAccessToken.scope)
                .expiresIn(atlassianAccessToken.expiresIn)
                .administrator(atlassianAccessToken.administrator)
                .created(atlassianAccessToken.created)
                .updated(atlassianAccessToken.updated);
    }

    public static class Builder {
        private UUID userId;
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private String scope;
        private Integer expiresIn;
        private boolean administrator;
        private ZonedDateTime created;
        private ZonedDateTime updated;

        private Builder() {
        }

        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder refreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
            return this;
        }

        public Builder tokenType(String tokenType) {
            this.tokenType = tokenType;
            return this;
        }

        public Builder scope(String scope) {
            this.scope = scope;
            return this;
        }

        public Builder expiresIn(Integer expiresIn) {
            this.expiresIn = expiresIn;
            return this;
        }

        public Builder administrator(boolean administrator) {
            this.administrator = administrator;
            return this;
        }

        public Builder created(ZonedDateTime created) {
            this.created = created;
            return this;
        }

        public Builder updated(ZonedDateTime updated) {
            this.updated = updated;
            return this;
        }

        public AtlassianAccessToken build() {
            return new AtlassianAccessToken(
                    userId,
                    accessToken,
                    refreshToken,
                    tokenType,
                    scope,
                    expiresIn,
                    administrator,
                    created,
                    updated,
                    null,
                    null
            );
        }
    }
}
