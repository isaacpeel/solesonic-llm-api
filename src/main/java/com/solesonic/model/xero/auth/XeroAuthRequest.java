package com.solesonic.model.xero.auth;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Body of a call to Xero's token endpoint. Covers both grants: {@code authorization_code} (with
 * {@code code} and {@code redirectUri}) and {@code refresh_token} (with {@code refreshToken}).
 * <p>
 * Sent as {@code application/x-www-form-urlencoded}, like Google's token endpoint and unlike
 * Atlassian's, which accepts JSON. Null fields are omitted rather than sent empty, since the two
 * grants share this one record and each leaves the other's fields unset — a null added to a
 * {@link MultiValueMap} would reach Xero as the literal string "null".
 */
public record XeroAuthRequest(
        String grantType,
        String clientId,
        String clientSecret,
        String refreshToken,
        String code,
        String redirectUri) {

    private static final String GRANT_TYPE = "grant_type";
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String CODE = "code";
    private static final String REDIRECT_URI = "redirect_uri";

    public MultiValueMap<String, String> formData() {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();

        addIfPresent(formData, GRANT_TYPE, grantType);
        addIfPresent(formData, CLIENT_ID, clientId);
        addIfPresent(formData, CLIENT_SECRET, clientSecret);
        addIfPresent(formData, REFRESH_TOKEN, refreshToken);
        addIfPresent(formData, CODE, code);
        addIfPresent(formData, REDIRECT_URI, redirectUri);

        return formData;
    }

    private static void addIfPresent(MultiValueMap<String, String> formData, String name, String value) {
        if (value != null) {
            formData.add(name, value);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String grantType;
        private String clientId;
        private String clientSecret;
        private String refreshToken;
        private String code;
        private String redirectUri;

        private Builder() {
        }

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

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder redirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
            return this;
        }

        public XeroAuthRequest build() {
            return new XeroAuthRequest(grantType, clientId, clientSecret, refreshToken, code, redirectUri);
        }
    }
}
