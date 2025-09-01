package com.solesonic.service.atlassian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsSecretsManagerAtlassianTokenStoreTest {

    @Mock
    private SecretsManagerClient secretsManagerClient;

    private AwsSecretsManagerAtlassianTokenStore tokenStore;
    private ObjectMapper objectMapper;
    private static final String SECRET_PREFIX = "/test/atlassian/tokens";
    private static final String ADMIN_KEY = "admin";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tokenStore = new AwsSecretsManagerAtlassianTokenStore(
                secretsManagerClient, objectMapper, SECRET_PREFIX, ADMIN_KEY);
    }

    @Test
    void load_whenTokenExists_returnsToken() throws Exception {
        UUID userId = UUID.randomUUID();
        AtlassianAccessToken expectedToken = createTestToken(userId);
        String tokenJson = objectMapper.writeValueAsString(expectedToken);

        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(tokenJson)
                .build();

        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        Optional<AtlassianAccessToken> result = tokenStore.load(userId);

        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(userId);
        assertThat(result.get().getAccessToken()).isEqualTo("test-access-token");

        ArgumentCaptor<GetSecretValueRequest> captor = ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(secretsManagerClient).getSecretValue(captor.capture());
        assertThat(captor.getValue().secretId()).isEqualTo(SECRET_PREFIX + "/" + userId);
    }

    @Test
    void load_whenTokenNotFound_returnsEmpty() {
        UUID userId = UUID.randomUUID();

        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().build());

        Optional<AtlassianAccessToken> result = tokenStore.load(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void save_whenSecretExists_updatesSecret() throws Exception {
        UUID userId = UUID.randomUUID();
        AtlassianAccessToken token = createTestToken(userId);

        tokenStore.save(token);

        ArgumentCaptor<PutSecretValueRequest> putCaptor = ArgumentCaptor.forClass(PutSecretValueRequest.class);
        verify(secretsManagerClient).putSecretValue(putCaptor.capture());
        
        PutSecretValueRequest putRequest = putCaptor.getValue();
        assertThat(putRequest.secretId()).isEqualTo(SECRET_PREFIX + "/" + userId);
        
        AtlassianAccessToken parsedToken = objectMapper.readValue(putRequest.secretString(), AtlassianAccessToken.class);
        assertThat(parsedToken.getUserId()).isEqualTo(userId);
        assertThat(parsedToken.getAccessToken()).isEqualTo("test-access-token");
    }

    @Test
    void save_whenSecretNotFound_createsSecret() throws Exception {
        UUID userId = UUID.randomUUID();
        AtlassianAccessToken token = createTestToken(userId);

        when(secretsManagerClient.putSecretValue(any(PutSecretValueRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().build());

        tokenStore.save(token);

        ArgumentCaptor<CreateSecretRequest> createCaptor = ArgumentCaptor.forClass(CreateSecretRequest.class);
        verify(secretsManagerClient).createSecret(createCaptor.capture());
        
        CreateSecretRequest createRequest = createCaptor.getValue();
        assertThat(createRequest.name()).isEqualTo(SECRET_PREFIX + "/" + userId);
        assertThat(createRequest.description()).isEqualTo("Atlassian access token");
        
        AtlassianAccessToken parsedToken = objectMapper.readValue(createRequest.secretString(), AtlassianAccessToken.class);
        assertThat(parsedToken.getUserId()).isEqualTo(userId);
    }

    @Test
    void save_whenTokenHasNoUserId_throwsException() {
        AtlassianAccessToken token = createTestToken(null);

        assertThatThrownBy(() -> tokenStore.save(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token must have a userId");
    }

    @Test
    void loadAdmin_whenTokenExists_returnsToken() throws Exception {
        AtlassianAccessToken expectedToken = createTestToken(null);
        expectedToken.setAdministrator(true);
        String tokenJson = objectMapper.writeValueAsString(expectedToken);

        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(tokenJson)
                .build();

        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        Optional<AtlassianAccessToken> result = tokenStore.loadAdmin();

        assertThat(result).isPresent();
        assertThat(result.get().isAdministrator()).isTrue();

        ArgumentCaptor<GetSecretValueRequest> captor = ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(secretsManagerClient).getSecretValue(captor.capture());
        assertThat(captor.getValue().secretId()).isEqualTo(SECRET_PREFIX + "/" + ADMIN_KEY);
    }

    @Test
    void saveAdmin_updatesAdminSecret() throws Exception {
        AtlassianAccessToken token = createTestToken(null);
        token.setAdministrator(true);

        tokenStore.saveAdmin(token);

        ArgumentCaptor<PutSecretValueRequest> putCaptor = ArgumentCaptor.forClass(PutSecretValueRequest.class);
        verify(secretsManagerClient).putSecretValue(putCaptor.capture());
        
        PutSecretValueRequest putRequest = putCaptor.getValue();
        assertThat(putRequest.secretId()).isEqualTo(SECRET_PREFIX + "/" + ADMIN_KEY);
        
        AtlassianAccessToken parsedToken = objectMapper.readValue(putRequest.secretString(), AtlassianAccessToken.class);
        assertThat(parsedToken.isAdministrator()).isTrue();
    }

    @Test
    void exists_whenSecretExists_returnsTrue() {
        UUID userId = UUID.randomUUID();

        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder().build());

        Optional<Boolean> result = tokenStore.exists(userId);

        assertThat(result).contains(true);
    }

    @Test
    void exists_whenSecretNotFound_returnsFalse() {
        UUID userId = UUID.randomUUID();

        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().build());

        Optional<Boolean> result = tokenStore.exists(userId);

        assertThat(result).contains(false);
    }

    private AtlassianAccessToken createTestToken(UUID userId) {
        AtlassianAccessToken token = new AtlassianAccessToken();
        token.setUserId(userId);
        token.setAccessToken("test-access-token");
        token.setRefreshToken("test-refresh-token");
        token.setScope("read write");
        token.setExpiresIn(3600);
        token.setAdministrator(false);
        token.setCreated(ZonedDateTime.now());
        token.setUpdated(ZonedDateTime.now());
        return token;
    }
}