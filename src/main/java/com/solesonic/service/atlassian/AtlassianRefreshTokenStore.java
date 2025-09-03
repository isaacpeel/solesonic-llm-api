package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.exception.atlassian.RefreshTokenConflictException;
import com.solesonic.model.atlassian.broker.AtlassianTokenRefreshResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class AtlassianRefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(AtlassianRefreshTokenStore.class);

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;
    private final String secretPrefix;

    public AtlassianRefreshTokenStore(SecretsManagerClient secretsManagerClient,
                                      ObjectMapper objectMapper,
                                      @Value("${atlassian.tokens.secrets.prefix:/solesonic/atlassian/tokens}") String secretPrefix) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = objectMapper;
        this.secretPrefix = secretPrefix;
    }

    public Optional<AtlassianTokenRefreshResponse> loadRefreshToken(UUID userId, String siteId) {
        String secretName = buildSecretName(userId, siteId);
        log.debug("Loading refresh token for user {} from secret {}", userId, secretName);

        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
        String secretValue = response.secretString();

        AtlassianTokenRefreshResponse payload;

        try {
            payload = objectMapper.readValue(secretValue, AtlassianTokenRefreshResponse.class);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RuntimeException(jsonProcessingException);
        }

        log.debug("Successfully loaded refresh token payload for user {}", userId);

        return Optional.of(payload);
    }

    public void saveRefreshToken(UUID userId, String siteId, AtlassianTokenRefreshResponse payload) {
        String secretName = buildSecretName(userId, siteId);
        log.debug("Saving refresh token for user {} to secret {}", userId, secretName);

        // Create new instance with updated timestamp and rotation counter
        ZonedDateTime now = ZonedDateTime.now();
        Integer rotationCounter = payload.rotationCounter() != null ? payload.rotationCounter() : 0;

        AtlassianTokenRefreshResponse updatedPayload = new AtlassianTokenRefreshResponse(
                payload,
                now,
                rotationCounter
        );

        String secretValue;

        try {
            secretValue = objectMapper.writeValueAsString(updatedPayload);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RuntimeException(jsonProcessingException);
        }

        PutSecretValueRequest putRequest = PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(secretValue)
                .build();

        secretsManagerClient.putSecretValue(putRequest);
        log.debug("Successfully saved refresh token for user {} to secret {}", userId, secretName);
    }

    public void updateRefreshTokenWithRotation(UUID userId, String siteId, String expectedOldRefreshToken) {
        String secretName = buildSecretName(userId, siteId);
        log.debug("Updating refresh token with rotation for user {} in secret {}", userId, secretName);

        Optional<AtlassianTokenRefreshResponse> currentPayload = loadRefreshToken(userId, siteId);

        if (currentPayload.isEmpty()) {
            log.warn("Cannot update refresh token - no existing token found for user {}", userId);
            throw new RuntimeException("No existing refresh token found for user " + userId);
        }

        AtlassianTokenRefreshResponse payload = currentPayload.get();

        // Check if the stored refresh token matches what we expect (best-effort CAS)
        if (!expectedOldRefreshToken.equals(payload.refreshToken())) {
            log.warn("Refresh token changed underneath for user {} - expected rotation conflict", userId);
            throw new RefreshTokenConflictException("Refresh token changed during rotation for user " + userId);
        }

        // Create new instance with updated refresh token and incremented rotation counter
        ZonedDateTime now = ZonedDateTime.now();
        Integer newRotationCounter = (payload.rotationCounter() != null ? payload.rotationCounter() : 0) + 1;

        AtlassianTokenRefreshResponse updatedPayload = new AtlassianTokenRefreshResponse(
                payload,
                now,
                newRotationCounter
        );

        saveRefreshToken(userId, siteId, updatedPayload);
        log.debug("Successfully rotated refresh token for user {} (rotation counter: {})", userId, newRotationCounter);
    }

    private String buildSecretName(UUID userId, String siteId) {
        if (siteId != null && !siteId.trim().isEmpty()) {
            return secretPrefix + "/" + siteId + "/" + userId;
        } else {
            return secretPrefix + "/" + userId;
        }
    }
}