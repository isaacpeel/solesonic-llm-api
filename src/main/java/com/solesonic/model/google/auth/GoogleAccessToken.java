package com.solesonic.model.google.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.jspecify.annotations.NonNull;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A user's Google OAuth2 token. Doubles as the JSON shape Google's token endpoint returns and as
 * the value persisted, encrypted, on {@code user_preferences.google_access_token}.
 * <p>
 * Unlike the Atlassian equivalent, {@code userId} is a normally readable property rather than
 * {@code READ_ONLY}: this record round-trips through Jackson inside
 * {@link GoogleAccessTokenConverter}, and a write-only-on-serialize field would come back null on
 * every read from the database.
 */
public record GoogleAccessToken(
        UUID userId,

        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("refresh_token")
        String refreshToken,

        @JsonProperty("token_type")
        String tokenType,

        String scope,

        @JsonProperty("expires_in")
        Integer expiresIn,

        @JsonProperty("refresh_token_expires_in")
        Integer refreshTokenExpiresIn,

        ZonedDateTime created,

        ZonedDateTime updated,

        String error,

        @JsonProperty("error_description")
        String errorDescription
) {

    /**
     * Ten seconds of skew, so a token that would expire in flight is refreshed instead of sent.
     * A token with no expiry information is treated as expired rather than trusted.
     */
    public boolean isExpired() {
        if (expiresIn == null || created == null) {
            return true;
        }

        ZonedDateTime expirationTime = created.plusSeconds(expiresIn).minusSeconds(10);

        return ZonedDateTime.now().isAfter(expirationTime);
    }

    /**
     * Overridden because a record's generated {@code toString()} renders every component, which
     * would put {@code accessToken} and {@code refreshToken} in plaintext into any log line,
     * exception message or debugger dump that reached for the object. Redacting here removes the
     * whole class of mistake instead of relying on every future call site to remember.
     * <p>
     * Secrets render as a fixed marker rather than a length, prefix or hash: a partial token is
     * still credential material and a stable hash is a correlation handle. Absent stays
     * distinguishable from present, which matters more here than anywhere else — Google returns a
     * refresh token only on a fresh grant, so whether one came back at all is the usual question.
     */
    @Override
    @NonNull
    public String toString() {
        return "GoogleAccessToken[userId=" + userId
                + ", accessToken=" + redacted(accessToken)
                + ", refreshToken=" + redacted(refreshToken)
                + ", tokenType=" + tokenType
                + ", scope=" + scope
                + ", expiresIn=" + expiresIn
                + ", refreshTokenExpiresIn=" + refreshTokenExpiresIn
                + ", created=" + created
                + ", updated=" + updated
                + ", error=" + error
                + ", errorDescription=" + errorDescription
                + "]";
    }

    private static String redacted(String secret) {
        return secret == null ? "null" : "[redacted]";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder from(GoogleAccessToken googleAccessToken) {
        return new Builder()
                .userId(googleAccessToken.userId)
                .accessToken(googleAccessToken.accessToken)
                .refreshToken(googleAccessToken.refreshToken)
                .tokenType(googleAccessToken.tokenType)
                .scope(googleAccessToken.scope)
                .expiresIn(googleAccessToken.expiresIn)
                .refreshTokenExpiresIn(googleAccessToken.refreshTokenExpiresIn)
                .created(googleAccessToken.created)
                .updated(googleAccessToken.updated);
    }

    public static class Builder {
        private UUID userId;
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private String scope;
        private Integer expiresIn;
        private Integer refreshTokenExpiresIn;
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

        public Builder refreshTokenExpiresIn(Integer refreshTokenExpiresIn) {
            this.refreshTokenExpiresIn = refreshTokenExpiresIn;
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

        /**
         * Always clears {@code error}/{@code errorDescription}: the builder is only ever used to
         * assemble a usable token, never to carry an error response forward.
         */
        public GoogleAccessToken build() {
            return new GoogleAccessToken(
                    userId,
                    accessToken,
                    refreshToken,
                    tokenType,
                    scope,
                    expiresIn,
                    refreshTokenExpiresIn,
                    created,
                    updated,
                    null,
                    null
            );
        }
    }
}
