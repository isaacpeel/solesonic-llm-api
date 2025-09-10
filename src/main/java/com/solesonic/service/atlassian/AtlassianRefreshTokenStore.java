package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.model.atlassian.broker.AtlassianTokenRefreshResponse;
import com.solesonic.service.aws.AwsSecretsManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class AtlassianRefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(AtlassianRefreshTokenStore.class);

    private final ObjectMapper objectMapper;

    @Value("${atlassian.tokens.secrets.prefix:/solesonic/atlassian/tokens}")
    private String secretPrefix;

    private final AwsSecretsManagerService awsSecretsManagerService;

    public AtlassianRefreshTokenStore(ObjectMapper objectMapper,
                                      AwsSecretsManagerService awsSecretsManagerService) {
        this.objectMapper = objectMapper;
        this.awsSecretsManagerService = awsSecretsManagerService;
    }

    public Optional<AtlassianTokenRefreshResponse> loadRefreshToken(UUID userId, String siteId) {
        String secretName = awsSecretsManagerService.buildSecretName(secretPrefix, userId, siteId);
        String secretValue = awsSecretsManagerService.findSecret(secretName);

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
        String secretName = awsSecretsManagerService.buildSecretName(secretPrefix, userId, siteId);
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

        awsSecretsManagerService.updateSecret(secretName, secretValue);
        log.debug("Successfully saved refresh token for user {} to secret {}", userId, secretName);
    }
}