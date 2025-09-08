package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.exception.atlassian.AtlassianTokenException;
import com.solesonic.exception.atlassian.RefreshTokenConflictException;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.atlassian.auth.AtlassianAuthRequest;
import com.solesonic.model.atlassian.auth.CachedAccessToken;
import com.solesonic.model.atlassian.broker.AtlassianTokenRefreshResponse;
import com.solesonic.model.atlassian.broker.TokenExchange;
import com.solesonic.model.atlassian.broker.TokenResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import static com.solesonic.service.atlassian.JiraAuthService.OAUTH_PATH;
import static com.solesonic.service.atlassian.JiraAuthService.TOKEN_PATH;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class AtlassianTokenBrokerService {

    private static final Logger log = LoggerFactory.getLogger(AtlassianTokenBrokerService.class);
    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";

    private final AtlassianRefreshTokenStore refreshTokenStore;
    private final AccessTokenCache accessTokenCache;
    private final String atlassianTokenUri;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper;

    // Per-user rotation guards to prevent concurrent refreshes within the same instance
    private final Map<String, ReentrantLock> rotationGuards = new ConcurrentHashMap<>();
    private static final long ROTATION_GUARD_TIMEOUT_MS = 30000; // 30 seconds max hold time

    public AtlassianTokenBrokerService(AtlassianRefreshTokenStore refreshTokenStore,
                                       AccessTokenCache accessTokenCache,
                                       @Value("${atlassian.oauth.token-uri:https://auth.atlassian.com/oauth/token}") String atlassianTokenUri,
                                       @Value("${atlassian.oauth.client-id}") String clientId,
                                       @Value("${atlassian.oauth.client-secret}") String clientSecret, ObjectMapper objectMapper) {
        this.refreshTokenStore = refreshTokenStore;
        this.accessTokenCache = accessTokenCache;
        this.atlassianTokenUri = atlassianTokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.objectMapper = objectMapper;
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
            throw new AtlassianTokenException("No refresh token found for user " + userId, BAD_REQUEST, false);
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
                throw new AtlassianTokenException("Rotation timeout for user " + userId, SERVICE_UNAVAILABLE, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlassianTokenException("Rotation interrupted for user " + userId, SERVICE_UNAVAILABLE, true, e);
        }
    }

    private TokenResponse refreshTokenWithRotation(UUID userId,
                                                   String siteId,
                                                   AtlassianTokenRefreshResponse payload) {

        String oldRefreshToken = payload.refreshToken();

        log.debug("Refreshing access token for user {} using refresh token", userId);

        AtlassianAccessToken atlassianAccessToken = refreshAtlassianToken(oldRefreshToken);

        if(StringUtils.isNotEmpty(atlassianAccessToken.error())) {
            String error = atlassianAccessToken.error();
            String errorDescription = atlassianAccessToken.errorDescription();

            String exceptionMessage = error+": "+errorDescription;

            throw new AtlassianTokenException(exceptionMessage, SERVICE_UNAVAILABLE, true);
        }

        ZonedDateTime issuedAt = ZonedDateTime.now();

        // If Atlassian returned a new refresh token, we need to rotate it
        if (atlassianAccessToken.hasNewRefreshToken(oldRefreshToken)) {

            log.debug("Atlassian returned new refresh token, performing rotation for user {}", userId);

            try {
                // Create new token response with the NEW refresh token from Atlassian
                AtlassianTokenRefreshResponse newTokenResponse = new AtlassianTokenRefreshResponse(
                        atlassianAccessToken.accessToken(),
                        atlassianAccessToken.refreshToken(), // NEW refresh token from Atlassian
                        atlassianAccessToken.expiresIn(),
                        payload.scope(),
                        payload.created(),
                        ZonedDateTime.now(),
                        (payload.rotationCounter() != null ? payload.rotationCounter() : 0) + 1
                );
                
                refreshTokenStore.saveRefreshToken(userId, siteId, newTokenResponse);

                log.debug("Successfully saved new refresh token for user {}", userId);

            } catch (RefreshTokenConflictException refreshTokenConflictException) {
                log.warn("Refresh token conflict detected for user {} - attempting single retry", userId);

                Optional<AtlassianTokenRefreshResponse> latestPayload = refreshTokenStore.loadRefreshToken(userId, siteId);

                if (latestPayload.isPresent() && !latestPayload.get().hasNewRefreshToken(oldRefreshToken)) {
                    AtlassianAccessToken retriedToken = refreshAtlassianToken(latestPayload.get().refreshToken());

                    if(StringUtils.isEmpty(retriedToken.error())) {
                        String error = retriedToken.error();
                        String errorDescription = retriedToken.errorDescription();
                        String exceptionMessage = error+": "+errorDescription;

                        throw new AtlassianTokenException(exceptionMessage, SERVICE_UNAVAILABLE, true);
                    }

                    log.debug("Retry refresh successful for user {} after conflict", userId);

                    // Update response with retry result
                    atlassianAccessToken = retriedToken;

                    // Try to update with new token if it changed again
                    if (retriedToken.refreshToken() != null && !retriedToken.refreshToken().equals(latestPayload.get().refreshToken())) {
                        try {
                            // Create new token response with the NEW refresh token from retry
                            AtlassianTokenRefreshResponse retryTokenResponse = new AtlassianTokenRefreshResponse(
                                    retriedToken.accessToken(),
                                    retriedToken.refreshToken(), // NEW refresh token from retry
                                    retriedToken.expiresIn(),
                                    latestPayload.get().scope(),
                                    latestPayload.get().created(),
                                    ZonedDateTime.now(),
                                    (latestPayload.get().rotationCounter() != null ? latestPayload.get().rotationCounter() : 0) + 1
                            );
                            
                            refreshTokenStore.saveRefreshToken(userId, siteId, retryTokenResponse);
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
                atlassianAccessToken.accessToken(),
                issuedAt,
                atlassianAccessToken.expiresIn());

        log.debug("Successfully minted and cached access token for user {}", userId);

        return new TokenResponse(
                atlassianAccessToken.accessToken(),
                atlassianAccessToken.expiresIn(),
                issuedAt, userId, siteId);
    }

    private AtlassianAccessToken refreshAtlassianToken(String refreshToken) {
        AtlassianAuthRequest atlassianAuthRequest = AtlassianAuthRequest.builder()
                .grantType(GRANT_TYPE_REFRESH_TOKEN)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .refreshToken(refreshToken)
                .build();

        try {
            String authRequest = objectMapper.writeValueAsString(atlassianAuthRequest);
            assert authRequest != null;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        WebClient client = WebClient.create(atlassianTokenUri);

        String responseJson = client.post()
                .uri(uriBuilder ->
                        uriBuilder
                                .pathSegment(OAUTH_PATH)
                                .pathSegment(TOKEN_PATH)
                                .build()
                )
                .bodyValue(atlassianAuthRequest)
                .exchangeToMono(response -> response.bodyToMono(String.class))
                .block();

        try {
            AtlassianAccessToken atlassianAccessToken = objectMapper.readValue(responseJson, AtlassianAccessToken.class);

            if(StringUtils.isNotEmpty(atlassianAccessToken.error())) {
                String error = atlassianAccessToken.error();
                String errorDescription = atlassianAccessToken.errorDescription();
                String exceptionMessage = error+": "+errorDescription;

                throw new AtlassianTokenException(exceptionMessage, SERVICE_UNAVAILABLE, true);
            }

            log.debug("Token refresh successful");

            return atlassianAccessToken;

        } catch (JsonProcessingException e) {
            throw new AtlassianTokenException("Failed to parse token refresh response", BAD_REQUEST, false, e);
        }

    }
}