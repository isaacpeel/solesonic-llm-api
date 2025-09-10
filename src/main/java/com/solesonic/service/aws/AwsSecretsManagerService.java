package com.solesonic.service.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;

import java.util.UUID;

@Service
public class AwsSecretsManagerService {
    private static final Logger log =  LoggerFactory.getLogger(AwsSecretsManagerService.class);

    public static String SECRET_TEMPLATE = "/%s/%s/tokens/%s";

    private final SecretsManagerClient secretsManagerClient;

    public AwsSecretsManagerService(SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
    }

    public String buildSecretName(String secretPrefix, UUID userId, String siteId) {
        return SECRET_TEMPLATE.formatted(secretPrefix, siteId, userId);
    }

    public boolean exists(String secretName) {
        log.trace("Checking if secret name exists: {}", secretName);

        GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        try {
            secretsManagerClient.getSecretValue(getSecretValueRequest);
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    public String findSecret(String secretName) {
        log.debug("Getting secret json for secret name: {}", secretName);

        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
        return response.secretString();
    }

    public void updateSecret(String secretName, String secretJson) {
        log.debug("Updating secret json for secret name: {}", secretName);

        PutSecretValueRequest putSecretValueRequest = PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(secretJson)
                .build();

        secretsManagerClient.putSecretValue(putSecretValueRequest);
    }

    public void save(String secretName, String secretJson) {
        log.debug("Saving secret json for secret name: {}", secretName);

        if(exists(secretName)) {
            PutSecretValueRequest putSecretValueRequest = PutSecretValueRequest.builder()
                    .secretId(secretName)
                    .secretString(secretJson)
                    .build();

            secretsManagerClient.putSecretValue(putSecretValueRequest);
        } else {
            CreateSecretRequest createSecretRequest = CreateSecretRequest.builder()
                    .name(secretName)
                    .secretString(secretJson)
                    .build();

            secretsManagerClient.createSecret(createSecretRequest);
        }
    }
}
