package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;

import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.atlassian.AtlassianRefreshTokenStore.buildSecretName;

@Service
public class AtlassianTokenStore {

    private static final Logger log = LoggerFactory.getLogger(AtlassianTokenStore.class);

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;
    private final String secretPrefix;
    private final String adminKey;


    public AtlassianTokenStore(SecretsManagerClient secretsManagerClient,
                               ObjectMapper objectMapper,
                               @Value("${atlassian.tokens.secrets.prefix:/solesonic/atlassian/tokens}") String secretPrefix,
                               @Value("${atlassian.tokens.secrets.adminKey:admin}") String adminKey) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = objectMapper;
        this.secretPrefix = secretPrefix;
        this.adminKey = adminKey;
    }

    public Optional<AtlassianAccessToken> load(UUID userId) {
        String secretName = buildSecretName(secretPrefix, userId, "atlassian");
        return loadSecret(secretName);
    }

    public void save(AtlassianAccessToken token) {
        String secretName = buildSecretName(secretPrefix, token.userId(), "atlassian");
        saveSecret(secretName, token);
    }

    public Optional<AtlassianAccessToken> loadAdmin() {
        String secretName = buildSecretName(secretPrefix, UUID.fromString(adminKey), "atlassian");
        return loadSecret(secretName);
    }

    public void saveAdmin(AtlassianAccessToken token) {
        String secretName = buildSecretName(secretPrefix, UUID.fromString(adminKey), "atlassian");
        saveSecret(secretName, token);
    }

    public boolean exists(UUID userId) {
        log.debug("Checking if token exists for userId: {}", userId);

        String secretName = buildSecretName(secretPrefix, userId, "atlassian");

        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        try {
            secretsManagerClient.getSecretValue(request);
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    private Optional<AtlassianAccessToken> loadSecret(String secretName) {
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
        String secretValue = response.secretString();

        AtlassianAccessToken token;

        try {
            token = objectMapper.readValue(secretValue, AtlassianAccessToken.class);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RuntimeException(jsonProcessingException);
        }

        log.debug("Successfully loaded token from secret: {}", secretName);
        return Optional.of(token);

    }

    private void saveSecret(String secretName, AtlassianAccessToken token) {
        String secretValue;

        try {
            secretValue = objectMapper.writeValueAsString(token);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RuntimeException(jsonProcessingException);
        }

        PutSecretValueRequest putRequest = PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(secretValue)
                .build();

        try {
            secretsManagerClient.putSecretValue(putRequest);
        } catch (ResourceNotFoundException e) {
            CreateSecretRequest createSecretRequest = CreateSecretRequest.builder()
                    .name(secretName)
                    .secretString(secretValue)
                    .build();

            secretsManagerClient.createSecret(createSecretRequest);
        }
    }
}