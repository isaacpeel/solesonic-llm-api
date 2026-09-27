package com.solesonic.model.xero.auth;

import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Xero's token endpoint takes {@code application/x-www-form-urlencoded}, and one record serves both
 * grants. What each grant must <em>not</em> send is as load-bearing as what it must: Xero rejects a
 * request carrying both {@code code} and {@code refresh_token}, and a {@code null} added to a
 * {@link MultiValueMap} would be encoded as the literal string "null" rather than omitted.
 */
class XeroAuthRequestTest {

    @Test
    void buildsTheAuthorizationCodeGrantBody() {
        MultiValueMap<String, String> formData = XeroAuthRequest.builder()
                .grantType("authorization_code")
                .clientId("client-id")
                .clientSecret("client-secret")
                .code("authorization-code")
                .redirectUri("https://example.test/xero/auth/callback")
                .build()
                .formData();

        assertThat(formData.getFirst("grant_type")).isEqualTo("authorization_code");
        assertThat(formData.getFirst("client_id")).isEqualTo("client-id");
        assertThat(formData.getFirst("client_secret")).isEqualTo("client-secret");
        assertThat(formData.getFirst("code")).isEqualTo("authorization-code");
        assertThat(formData.getFirst("redirect_uri")).isEqualTo("https://example.test/xero/auth/callback");
    }

    @Test
    void omitsRefreshTokenFromTheAuthorizationCodeGrant() {
        MultiValueMap<String, String> formData = XeroAuthRequest.builder()
                .grantType("authorization_code")
                .clientId("client-id")
                .clientSecret("client-secret")
                .code("authorization-code")
                .redirectUri("https://example.test/xero/auth/callback")
                .build()
                .formData();

        assertThat(formData).doesNotContainKey("refresh_token");
    }

    @Test
    void buildsTheRefreshTokenGrantBody() {
        MultiValueMap<String, String> formData = XeroAuthRequest.builder()
                .grantType("refresh_token")
                .clientId("client-id")
                .clientSecret("client-secret")
                .refreshToken("stored-refresh-token")
                .build()
                .formData();

        assertThat(formData.getFirst("grant_type")).isEqualTo("refresh_token");
        assertThat(formData.getFirst("refresh_token")).isEqualTo("stored-refresh-token");
        assertThat(formData).doesNotContainKey("code");
        assertThat(formData).doesNotContainKey("redirect_uri");
    }
}
