package com.solesonic.model.atlassian.auth;

import java.time.ZonedDateTime;

public record CachedAccessToken(String accessToken, ZonedDateTime issuedAt, int expiresInSeconds) {
    
    public boolean isExpired(int skewSeconds) {
        ZonedDateTime expirationTime = issuedAt.plusSeconds(expiresInSeconds - skewSeconds);
        return ZonedDateTime.now().isAfter(expirationTime);
    }
}