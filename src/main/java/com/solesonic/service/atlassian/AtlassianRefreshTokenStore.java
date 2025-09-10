package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.model.atlassian.broker.AtlassianTokenRefreshResponse;
import com.solesonic.service.aws.AwsSecretsManagerService;
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

    ///prefix/site/tokens/userId
    public static String secretTemplate = "/%s/%s/tokens/%s";
    private final AwsSecretsManagerService awsSecretsManagerService;

    public AtlassianRefreshTokenStore(SecretsManagerClient secretsManagerClient,
                                      ObjectMapper objectMapper,
                                      @Value("${atlassian.tokens.secrets.prefix:/solesonic/atlassian/tokens}") String secretPrefix, AwsSecretsManagerService awsSecretsManagerService) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = objectMapper;
        this.secretPrefix = secretPrefix;
        this.awsSecretsManagerService = awsSecretsManagerService;
    }

    public Optional<AtlassianTokenRefreshResponse> loadRefreshToken(UUID userId, String siteId) {
        String secretName = awsSecretsManagerService.buildSecretName(secretPrefix, userId, siteId);
        String secretValue = awsSecretsManagerService.secretJson(secretName);

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