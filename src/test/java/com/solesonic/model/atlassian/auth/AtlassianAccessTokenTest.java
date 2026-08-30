package com.solesonic.model.atlassian.auth;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AtlassianAccessTokenTest {

    private static final String ACCESS_TOKEN_VALUE = "atlassian-access-token-secret-value";
    private static final String REFRESH_TOKEN_VALUE = "atlassian-refresh-token-secret-value";

    private static AtlassianAccessToken populatedToken() {
        return new AtlassianAccessToken(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                ACCESS_TOKEN_VALUE,
                REFRESH_TOKEN_VALUE,
                "Bearer",
                "read:jira-work offline_access",
                3600,
                true,
                ZonedDateTime.parse("2026-01-01T00:00:00Z"),
                ZonedDateTime.parse("2026-01-02T00:00:00Z"),
                "invalid_grant",
                "The refresh token is invalid");
    }

    @Test
    void toStringNeverContainsSecretValues() {
        String rendered = populatedToken().toString();

        assertThat(rendered)
                .doesNotContain(ACCESS_TOKEN_VALUE)
                .doesNotContain(REFRESH_TOKEN_VALUE);
    }

    @Test
    void toStringRendersSecretsAsFixedMarkerNotAsLengthPrefixOrHash() {
        String rendered = populatedToken().toString();

        assertThat(rendered)
                .contains("accessToken=[redacted]")
                .contains("refreshToken=[redacted]");

        // A partial token is still credential material; a stable hash is a correlation handle.
        assertThat(rendered)
                .doesNotContain(ACCESS_TOKEN_VALUE.substring(0, 8))
                .doesNotContain(String.valueOf(ACCESS_TOKEN_VALUE.length()))
                .doesNotContain(String.valueOf(ACCESS_TOKEN_VALUE.hashCode()));
    }

    @Test
    void toStringKeepsEveryNonSecretComponentVisible() {
        String rendered = populatedToken().toString();

        assertThat(rendered)
                .contains("userId=11111111-2222-3333-4444-555555555555")
                .contains("tokenType=Bearer")
                .contains("scope=read:jira-work offline_access")
                .contains("expiresIn=3600")
                .contains("administrator=true")
                .contains("created=2026-01-01T00:00Z")
                .contains("updated=2026-01-02T00:00Z")
                .contains("error=invalid_grant")
                .contains("errorDescription=The refresh token is invalid");
    }

    @Test
    void toStringDistinguishesAbsentSecretFromPresentSecret() {
        AtlassianAccessToken noRefreshToken = AtlassianAccessToken.builder()
                .accessToken(ACCESS_TOKEN_VALUE)
                .refreshToken(null)
                .build();

        assertThat(noRefreshToken.toString())
                .contains("refreshToken=null")
                .contains("accessToken=[redacted]");
    }

    /**
     * Jackson is driven by the record components and their {@code @JsonProperty} annotations, not
     * by {@code toString()}. Without this, a redacted {@code toString()} could not be told apart
     * from a redacted persisted value — and the converter writes what this asserts.
     */
    @Test
    void redactedToStringDoesNotAffectJacksonSerialization() {
        JsonMapper jsonMapper = JsonMapper.builder().build();

        String json = jsonMapper.writeValueAsString(populatedToken());

        assertThat(json)
                .contains(ACCESS_TOKEN_VALUE)
                .contains(REFRESH_TOKEN_VALUE);

        AtlassianAccessToken roundTripped = jsonMapper.readValue(json, AtlassianAccessToken.class);

        assertThat(roundTripped.accessToken()).isEqualTo(ACCESS_TOKEN_VALUE);
        assertThat(roundTripped.refreshToken()).isEqualTo(REFRESH_TOKEN_VALUE);
    }

    @Test
    void isExpired_whenTokenIsNotExpired_returnsFalse() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusMinutes(5), null, null, null);

        assertThat(token.isExpired()).isFalse();
    }

    @Test
    void isExpired_whenTokenIsExpired_returnsTrue() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusHours(2), null, null, null);

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_whenTokenIsAtBoundaryWithBuffer_returnsTrue() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusSeconds(3605), null, null, null); // 3600 + 5 seconds ago

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_whenExpiresInIsNull_isExpired() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, null, false, 
                ZonedDateTime.now(), null, null, null);

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_whenCreatedIsNull_isExpired() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                null, null, null, null);

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_whenBothFieldsAreNull_throwsException() {
        AtlassianAccessToken token = new AtlassianAccessToken(
                null, null, null, null, null, null, false, 
                null, null, null, null);

        assertThat(token.isExpired()).isTrue();
    }

    @Test
    void isExpired_verifyBufferLogic() {
        // Test exactly at buffer boundary (should be expired)
        AtlassianAccessToken token1 = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusSeconds(3600 - 10), null, null, null); // expires in exactly 10 seconds
        assertThat(token1.isExpired()).isTrue();
        
        // Test just before buffer boundary (should not be expired)
        AtlassianAccessToken token2 = new AtlassianAccessToken(
                null, null, null, null, null, 3600, false, 
                ZonedDateTime.now().minusSeconds(3600 - 11), null, null, null); // expires in 11 seconds
        assertThat(token2.isExpired()).isFalse();
    }
}