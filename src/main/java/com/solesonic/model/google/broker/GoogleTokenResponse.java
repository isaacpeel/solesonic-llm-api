package com.solesonic.model.google.broker;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.ZonedDateTime;
import java.util.UUID;

public record GoogleTokenResponse(
        @JsonProperty("accessToken") String accessToken,
        @JsonProperty("expiresInSeconds") int expiresInSeconds,
        @JsonProperty("issuedAt") ZonedDateTime issuedAt,
        @JsonProperty("userId") UUID userId) {
}
