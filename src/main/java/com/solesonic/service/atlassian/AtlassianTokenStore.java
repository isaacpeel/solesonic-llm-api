package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.service.aws.AwsSecretsManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;


@Service
public class AtlassianTokenStore {
    private static final Logger log = LoggerFactory.getLogger(AtlassianTokenStore.class);

    private final ObjectMapper objectMapper;
    private final AwsSecretsManagerService awsSecretsManagerService;

    @Value("${atlassian.tokens.secrets.adminKey:admin}")
    private String adminKey;

    @Value("${atlassian.tokens.secrets.prefix}") 
    private String secretPrefix;
            

    public AtlassianTokenStore(ObjectMapper objectMapper, AwsSecretsManagerService awsSecretsManagerService) {
        this.objectMapper = objectMapper;
        this.awsSecretsManagerService = awsSecretsManagerService;
    }

    public Optional<AtlassianAccessToken> load(UUID userId) {
        String secretName = awsSecretsManagerService.buildSecretName(secretPrefix, userId, "atlassian");
        return loadSecret(secretName);
    }

    public void save(AtlassianAccessToken token) {
        String secretName = awsSecretsManagerService.buildSecretName(secretPrefix, token.userId(), "atlassian");
        saveSecret(secretName, token);
    }

    public Optional<AtlassianAccessToken> loadAdmin() {
        String secretName = awsSecretsManagerService.buildSecretName(secretPrefix, UUID.fromString(adminKey), "atlassian");
        return loadSecret(secretName);
    }

    public void saveAdmin(AtlassianAccessToken token) {
        String secretName = awsSecretsManagerService.buildSecretName(secretPrefix, UUID.fromString(adminKey), "atlassian");
        saveSecret(secretName, token);
    }

    public boolean exists(UUID userId) {
        log.debug("Checking if token exists for userId: {}", userId);

        String secretName = awsSecretsManagerService.buildSecretName(secretPrefix, userId, "atlassian");

        return awsSecretsManagerService.exists(secretName);
    }

    private Optional<AtlassianAccessToken> loadSecret(String secretName) {
        String secretValue = awsSecretsManagerService.findSecret(secretName);

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
        try {
            String secretValue = objectMapper.writeValueAsString(token);
            awsSecretsManagerService.save(secretName, secretValue);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RuntimeException(jsonProcessingException);
        }
    }
}