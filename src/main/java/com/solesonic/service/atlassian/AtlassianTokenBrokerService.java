package com.solesonic.service.atlassian;

import com.solesonic.exception.AtlassianTokenException;
import com.solesonic.exception.RefreshTokenConflictException;
import com.solesonic.model.atlassian.auth.CachedAccessToken;
import com.solesonic.model.atlassian.broker.AtlassianTokenRefreshResponse;
import com.solesonic.model.atlassian.broker.TokenExchange;
import com.solesonic.model.atlassian.broker.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.solesonic.config.atlassian.AtlassianConstants.ATLASSIAN_AUTH_WEB_CLIENT;

@Service
public class AtlassianTokenBrokerService {

    private static final Logger log = LoggerFactory.getLogger(AtlassianTokenBrokerService.class);
    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    public static final String GRANT_TYPE = "grant_type";
    public static final String CLIENT_ID = "client_id";
    public static final String CLIENT_SECRET = "client_secret";
    public static final String REFRESH_TOKEN = "refresh_token";

    private final AtlassianRefreshTokenStore refreshTokenStore;
    private final AccessTokenCache accessTokenCache;
    private final WebClient webClient;
    private final String atlassianTokenUri;
    private final String clientId;
    private final String clientSecret;

    // Per-user rotation guards to prevent concurrent refreshes within the same instance
    private final Map<String, ReentrantLock> rotationGuards = new ConcurrentHashMap<>();
    private static final long ROTATION_GUARD_TIMEOUT_MS = 30000; // 30 seconds max hold time

    public AtlassianTokenBrokerService(AtlassianRefreshTokenStore refreshTokenStore,
                                       AccessTokenCache accessTokenCache,
                                       @Qualifier(ATLASSIAN_AUTH_WEB_CLIENT) WebClient webClient,
                                       @Value("${atlassian.oauth.token-uri:https://auth.atlassian.com/oauth/token}") String atlassianTokenUri,
                                       @Value("${atlassian.oauth.client-id}") String clientId,
                                       @Value("${atlassian.oauth.client-secret}") String clientSecret) {
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenCache = accessTokenCache;
        this.webClient = webClient;
        this.atlassianTokenUri = atlassianTokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }


    public TokenResponse mintToken(TokenExchange tokenExchange) {
        UUID userId = tokenExchange.subjectToken();
        String siteId = tokenExchange.audience();

        log.debug("Minting token for user {} siteId {}", userId, siteId);

        // First check cache for existing valid access token
        Optional<CachedAccessToken> cachedToken = accessTokenCache.get(userId, siteId);

        if (cachedToken.isPresent()) {
            CachedAccessToken cachedAccessToken = cachedToken.get();
            log.debug("Returning cached access token for user {}", userId);

            return new TokenResponse(
                    cachedAccessToken.accessToken(),
                    cachedAccessToken.expiresInSeconds(),
                    cachedAccessToken.issuedAt(),
                    userId,
                    siteId);
        }

        // Load refresh token from Secrets Manager
        Optional<AtlassianTokenRefreshResponse> refreshTokenPayload = refreshTokenStore.loadRefreshToken(userId, siteId);

        if (refreshTokenPayload.isEmpty()) {
            log.warn("No refresh token found for user {} - RECONNECT_REQUIRED", userId);
            throw new AtlassianTokenException("No refresh token found for user " + userId, "RECONNECT_REQUIRED", false);
        }

        // Use rotation guard to prevent concurrent refreshes
        String guardKey = userId + ":" + (siteId != null ? siteId : "");
        ReentrantLock guard = rotationGuards.computeIfAbsent(guardKey, k -> new ReentrantLock());

        try {
            if (guard.tryLock(ROTATION_GUARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                try {
                    return refreshTokenWithRotation(userId, siteId, refreshTokenPayload.get());
                } finally {
                    guard.unlock();
                }
            } else {
                log.warn("Failed to acquire rotation guard for user {} within timeout", userId);
                throw new AtlassianTokenException("Rotation timeout for user " + userId, "ROTATION_TIMEOUT", true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlassianTokenException("Rotation interrupted for user " + userId, "ROTATION_INTERRUPTED", true, e);
        }
    }

    private TokenResponse refreshTokenWithRotation(UUID userId, String siteId, AtlassianTokenRefreshResponse payload) {
        String oldRefreshToken = payload.refreshToken();

        log.debug("Refreshing access token for user {} using refresh token", userId);

        AtlassianTokenRefreshResponse atlassianResponse = refreshAtlassianToken(oldRefreshToken);

        ZonedDateTime issuedAt = ZonedDateTime.now();

        // If Atlassian returned a new refresh token, we need to rotate it
        if (atlassianResponse.hasNewRefreshToken(oldRefreshToken)) {

            log.debug("Atlassian returned new refresh token, performing rotation for user {}", userId);

            try {
                refreshTokenStore.updateRefreshTokenWithRotation(
                        userId,
                        siteId,
                        oldRefreshToken);

                log.debug("Successfully rotated refresh token for user {}", userId);

            } catch (RefreshTokenConflictException refreshTokenConflictException) {
                log.warn("Refresh token conflict detected for user {} - attempting single retry", userId);

                Optional<AtlassianTokenRefreshResponse> latestPayload = refreshTokenStore.loadRefreshToken(userId, siteId);

                if (latestPayload.isPresent() && !latestPayload.get().hasNewRefreshToken(oldRefreshToken)) {
                    AtlassianTokenRefreshResponse retryResponse = refreshAtlassianToken(latestPayload.get().refreshToken());
                    log.debug("Retry refresh successful for user {} after conflict", userId);

                    // Update response with retry result
                    atlassianResponse = retryResponse;

                    // Try to update with new token if it changed again
                    if (retryResponse.refreshToken() != null && !retryResponse.refreshToken().equals(latestPayload.get().refreshToken())) {
                        try {
                            refreshTokenStore.updateRefreshTokenWithRotation(
                                    userId,
                                    siteId,
                                    latestPayload.get().refreshToken());
                        } catch (RefreshTokenConflictException retryConflict) {
                            log.warn("Second conflict detected for user {} - proceeding with current token", userId);
                        }
                    }
                }
            }
        }

        accessTokenCache.put(
                userId,
                siteId,
                atlassianResponse.accessToken(),
                issuedAt,
                atlassianResponse.expiresIn());

        log.debug("Successfully minted and cached access token for user {}", userId);

        return new TokenResponse(
                atlassianResponse.accessToken(),
                atlassianResponse.expiresIn(),
                issuedAt, userId, siteId);
    }

    private AtlassianTokenRefreshResponse refreshAtlassianToken(String refreshToken) {
        return webClient.post()
                .uri(atlassianTokenUri)
                .bodyValue(Map.of(
                        GRANT_TYPE, GRANT_TYPE_REFRESH_TOKEN,
                        CLIENT_ID, clientId,
                        CLIENT_SECRET, clientSecret,
                        REFRESH_TOKEN, refreshToken
                ))
                .retrieve()
                .bodyToMono(AtlassianTokenRefreshResponse.class)
                .block();
    }
}