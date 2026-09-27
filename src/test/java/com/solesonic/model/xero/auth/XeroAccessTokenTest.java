package com.solesonic.model.xero.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the redacted {@code toString()}. These assertions are the only thing stopping a future
 * {@code log.debug("... {}", token)} from writing a live refresh token to disk, so they are
 * deliberately explicit about both halves: what must never appear, and what must still appear.
 */
class XeroAccessTokenTest {

    private static final String ACCESS_TOKEN_VALUE = "xero-access-token-secret-value";
    private static final String REFRESH_TOKEN_VALUE = "xero-refresh-token-secret-value";

    private static XeroAccessToken populatedToken() {
        return new XeroAccessToken(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                ACCESS_TOKEN_VALUE,
                REFRESH_TOKEN_VALUE,
                "Bearer",
                "accounting.transactions offline_access",
                1800,
                "tenant-id-value",
                "Tenant Name",
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
                .contains("scope=accounting.transactions offline_access")
                .contains("expiresIn=1800")
                .contains("tenantId=tenant-id-value")
                .contains("tenantName=Tenant Name")
                .contains("created=2026-01-01T00:00Z")
                .contains("updated=2026-01-02T00:00Z")
                .contains("error=invalid_grant")
                .contains("errorDescription=The refresh token is invalid");
    }

    @Test
    void toStringDistinguishesAbsentSecretFromPresentSecret() {
        XeroAccessToken noRefreshToken = XeroAccessToken.builder()
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

        XeroAccessToken roundTripped = jsonMapper.readValue(json, XeroAccessToken.class);

        assertThat(roundTripped.accessToken()).isEqualTo(ACCESS_TOKEN_VALUE);
        assertThat(roundTripped.refreshToken()).isEqualTo(REFRESH_TOKEN_VALUE);
    }
}
