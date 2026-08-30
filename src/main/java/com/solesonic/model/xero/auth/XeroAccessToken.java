package com.solesonic.model.xero.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A user's Xero OAuth2 token. Doubles as the JSON shape Xero's token endpoint returns and as the
 * value persisted, encrypted, on {@code user_preferences.xero_access_token}.
 * <p>
 * Shaped after {@link com.solesonic.model.google.auth.GoogleAccessToken} rather than the Atlassian
 * equivalent, with two differences that are specific to Xero:
 * <ul>
 *     <li>{@code tenantId}/{@code tenantName} replace Google's {@code refresh_token_expires_in}.
 *     They are not part of Xero's token response — they are resolved separately from
 *     {@code GET /connections} and stamped on afterwards, because {@code tenantId} has to travel on
 *     the {@code xero-tenant-id} header of every Accounting API call.</li>
 *     <li>Xero rotates the refresh token on every refresh and invalidates the old one immediately,
 *     so there is no "carry the previous refresh token forward" branch anywhere in this
 *     integration — unlike Google's.</li>
 * </ul>
 * As with the Google record, {@code userId} is a normally readable property rather than
 * {@code READ_ONLY}: this record round-trips through Jackson inside
 * {@link XeroAccessTokenConverter}, and a write-only-on-serialize field would come back null on
 * every read from the database.
 */
public record XeroAccessToken(
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

        String tenantId,

        String tenantName,

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

    public static Builder builder() {
        return new Builder();
    }

    public static Builder from(XeroAccessToken xeroAccessToken) {
        return new Builder()
                .userId(xeroAccessToken.userId)
                .accessToken(xeroAccessToken.accessToken)
                .refreshToken(xeroAccessToken.refreshToken)
                .tokenType(xeroAccessToken.tokenType)
                .scope(xeroAccessToken.scope)
                .expiresIn(xeroAccessToken.expiresIn)
                .tenantId(xeroAccessToken.tenantId)
                .tenantName(xeroAccessToken.tenantName)
                .created(xeroAccessToken.created)
                .updated(xeroAccessToken.updated);
    }

    public static class Builder {
        private UUID userId;
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private String scope;
        private Integer expiresIn;
        private String tenantId;
        private String tenantName;
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

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder tenantName(String tenantName) {
            this.tenantName = tenantName;
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
        public XeroAccessToken build() {
            return new XeroAccessToken(
                    userId,
                    accessToken,
                    refreshToken,
                    tokenType,
                    scope,
                    expiresIn,
                    tenantId,
                    tenantName,
                    created,
                    updated,
                    null,
                    null
            );
        }
    }
}
