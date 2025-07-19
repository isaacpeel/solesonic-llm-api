package com.solesonic.izzybot.model.atlassian.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AtlassianAuthRequest(
        @JsonProperty("grant_type") String grantType,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("client_secret") String clientSecret,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("access_token") String accessToken,
        String code,
        @JsonProperty("redirect_uri") String redirectUri) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String grantType;
        private String clientId;
        private String clientSecret;
        private String refreshToken;
        private String accessToken;
        private String code;
        private String redirectUri;

        public Builder grantType(String grantType) {
            this.grantType = grantType;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder clientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
            return this;
        }

        public Builder refreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
            return this;
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder redirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
            return this;
        }

        public AtlassianAuthRequest build() {
            return new AtlassianAuthRequest(grantType, clientId, clientSecret, refreshToken, accessToken, code, redirectUri);
        }
    }
}
