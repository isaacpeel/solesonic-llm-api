package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.exception.atlassian.AtlassianTokenException;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.atlassian.auth.AtlassianAuthRequest;
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
    private final String atlassianTokenUri;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper;

    // Per-user rotation guards to prevent concurrent refreshes within the same instance
    private final Map<String, ReentrantLock> rotationGuards = new ConcurrentHashMap<>();
    private static final long ROTATION_GUARD_TIMEOUT_MS = 30000; // 30 seconds max hold time

    public AtlassianTokenBrokerService(AtlassianRefreshTokenStore refreshTokenStore,
                                       @Value("${atlassian.oauth.token-uri:https://auth.atlassian.com/oauth/token}") String atlassianTokenUri,
                                       @Value("${atlassian.oauth.client-id}") String clientId,
                                       @Value("${atlassian.oauth.client-secret}") String clientSecret, ObjectMapper objectMapper) {
        this.refreshTokenStore = refreshTokenStore;
        this.atlassianTokenUri = atlassianTokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.objectMapper = objectMapper;
    }


    public TokenResponse mintToken(TokenExchange tokenExchange) {
        UUID userId = tokenExchange.subjectToken();
        String siteId = tokenExchange.audience();

        log.debug("Minting token for user {} siteId {}", userId, siteId);

        Optional<AtlassianAccessToken> atlassianAccessToken = refreshTokenStore.loadRefreshToken(userId);

        if (atlassianAccessToken.isEmpty()) {
            log.warn("No refresh token found for user {} - RECONNECT_REQUIRED", userId);
            throw new AtlassianTokenException("No refresh token found for user " + userId, BAD_REQUEST, false);
        }

        // Use rotation guard to prevent concurrent refreshes
        String guardKey = userId + ":" + (siteId != null ? siteId : "");
        ReentrantLock guard = rotationGuards.computeIfAbsent(guardKey, k -> new ReentrantLock());

        try {
            if (guard.tryLock(ROTATION_GUARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                try {
                    return refreshTokenWithRotation(userId, atlassianAccessToken.get());
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
                                                   AtlassianAccessToken atlassianAccessToken) {

        String oldRefreshToken = atlassianAccessToken.refreshToken();

        log.debug("Refreshing access token for user {} using refresh token", userId);

        AtlassianAccessToken refreshedAtlassianToken = refreshAtlassianToken(oldRefreshToken);

        if (StringUtils.isNotEmpty(refreshedAtlassianToken.error())) {
            String error = refreshedAtlassianToken.error();
            String errorDescription = refreshedAtlassianToken.errorDescription();

            String exceptionMessage = error + ": " + errorDescription;

            throw new AtlassianTokenException(exceptionMessage, SERVICE_UNAVAILABLE, true);
        }

        ZonedDateTime issuedAt = ZonedDateTime.now();

        // If Atlassian returned a new refresh token, we need to rotate it
        if (refreshedAtlassianToken.hasNewRefreshToken(oldRefreshToken)) {
            log.debug("Atlassian returned new refresh token, performing rotation for user {}", userId);

            refreshTokenStore.saveRefreshToken(userId, refreshedAtlassianToken);

            log.debug("Successfully saved new refresh token for user {}", userId);

        }

        log.debug("Successfully minted and cached access token for user {}", userId);

        return new TokenResponse(
                refreshedAtlassianToken.accessToken(),
                refreshedAtlassianToken.expiresIn(),
                issuedAt,
                userId);
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

            if (StringUtils.isNotEmpty(atlassianAccessToken.error())) {
                String error = atlassianAccessToken.error();
                String errorDescription = atlassianAccessToken.errorDescription();
                String exceptionMessage = error + ": " + errorDescription;

                throw new AtlassianTokenException(exceptionMessage, SERVICE_UNAVAILABLE, true);
            }

            log.debug("Token refresh successful");

            return atlassianAccessToken;

        } catch (JsonProcessingException e) {
            throw new AtlassianTokenException("Failed to parse token refresh response", BAD_REQUEST, false, e);
        }

    }
}