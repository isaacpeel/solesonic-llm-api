package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;

import java.util.Optional;
import java.util.UUID;

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
        String secretName = secretPrefix + "/" + userId.toString();
        return loadSecret(secretName);
    }

    public void save(AtlassianAccessToken token) {
        String secretName = secretPrefix + "/" + token.getUserId().toString();
        saveSecret(secretName, token);
    }

    public Optional<AtlassianAccessToken> loadAdmin() {
        String secretName = secretPrefix + "/" + adminKey;
        return loadSecret(secretName);
    }

    public void saveAdmin(AtlassianAccessToken token) {
        String secretName = secretPrefix + "/" + adminKey;
        saveSecret(secretName, token);
    }

    public Optional<Boolean> exists(UUID userId) {
        String secretName = secretPrefix + "/" + userId.toString();

        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        secretsManagerClient.getSecretValue(request);
        return Optional.of(true);
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
        log.debug("Successfully loaded token from secret: " +
                "{}", secretName);
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

        secretsManagerClient.putSecretValue(putRequest);
        log.debug("Successfully updated existing secret: {}", secretName);
    }
}