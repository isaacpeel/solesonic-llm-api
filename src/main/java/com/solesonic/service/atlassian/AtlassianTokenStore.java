package com.solesonic.service.atlassian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

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
        if (token.getUserId() == null) {
            throw new IllegalArgumentException("Token must have a userId");
        }
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
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            
            secretsManagerClient.getSecretValue(request);
            return Optional.of(true);
        } catch (ResourceNotFoundException e) {
            return Optional.of(false);
        } catch (SecretsManagerException e) {
            log.warn("Error checking if secret exists for user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }
    
    private Optional<AtlassianAccessToken> loadSecret(String secretName) {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretValue = response.secretString();
            
            AtlassianAccessToken token = objectMapper.readValue(secretValue, AtlassianAccessToken.class);
            log.debug("Successfully loaded token from secret: {}", secretName);
            return Optional.of(token);
            
        } catch (ResourceNotFoundException e) {
            log.debug("Secret not found: {}", secretName);
            return Optional.empty();
        } catch (JsonProcessingException e) {
            log.error("Error deserializing token from secret {}: {}", secretName, e.getMessage());
            throw new RuntimeException("Failed to deserialize token", e);
        } catch (SecretsManagerException e) {
            log.error("Error loading secret {}: {}", secretName, e.getMessage());
            throw new RuntimeException("Failed to load secret", e);
        }
    }
    
    private void saveSecret(String secretName, AtlassianAccessToken token) {
        try {
            String secretValue = objectMapper.writeValueAsString(token);
            
            try {
                PutSecretValueRequest putRequest = PutSecretValueRequest.builder()
                        .secretId(secretName)
                        .secretString(secretValue)
                        .build();
                
                secretsManagerClient.putSecretValue(putRequest);
                log.debug("Successfully updated existing secret: {}", secretName);
                
            } catch (ResourceNotFoundException e) {
                CreateSecretRequest createRequest = CreateSecretRequest.builder()
                        .name(secretName)
                        .secretString(secretValue)
                        .description("Atlassian access token")
                        .build();
                
                secretsManagerClient.createSecret(createRequest);
                log.debug("Successfully created new secret: {}", secretName);
            }
            
        } catch (JsonProcessingException e) {
            log.error("Error serializing token for secret {}: {}", secretName, e.getMessage());
            throw new RuntimeException("Failed to serialize token", e);
        } catch (SecretsManagerException e) {
            log.error("Error saving secret {}: {}", secretName, e.getMessage());
            throw new RuntimeException("Failed to save secret", e);
        }
    }
}