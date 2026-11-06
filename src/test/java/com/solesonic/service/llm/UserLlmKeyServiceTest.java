package com.solesonic.service.llm;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.models.ModelListPage;
import com.openai.models.models.ModelListParams;
import com.openai.services.blocking.ModelService;
import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.exception.llm.LlmKeyRejectedException;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.model.llm.LlmKeyErrorCode;
import com.solesonic.model.llm.UserLlmKeySummary;
import com.solesonic.repository.llm.UserLlmKeyRepository;
import com.solesonic.repository.llm.UserLlmKeyRepository.StoredLlmKey;
import com.solesonic.security.crypto.CryptoConfig;
import com.solesonic.service.user.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLlmKeyServiceTest {

    private static final String SERVICE_KEY = "sk-service-0000000000";
    private static final String USER_KEY = "sk-user-key-00001234";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final BytesEncryptor bytesEncryptor = new CryptoConfig().bytesEncryptor("test-password", "0123456789abcdef");

    @Mock
    private UserLlmKeyRepository userLlmKeyRepository;
    @Mock
    private OpenAIClient openAiClient;
    @Mock
    private ModelService modelService;
    @Mock
    private ModelListPage modelListPage;
    @Mock
    private UserProfileService userProfileService;

    private UserLlmKeyService userLlmKeyService;

    @BeforeEach
    void setUp() {
        userLlmKeyService = new UserLlmKeyService(userLlmKeyRepository, bytesEncryptor, openAiClient, userProfileService, SERVICE_KEY);
    }

    private void modelServerAccepts() {
        when(openAiClient.models()).thenReturn(modelService);
        when(modelService.list(any(ModelListParams.class))).thenReturn(modelListPage);
    }

    private static StoredLlmKey stored(UUID userId) {
        ZonedDateTime now = ZonedDateTime.now();

        return new StoredLlmKey(userId, "1234", now, now, ADMIN_ID);
    }

    @Test
    void storesTheKeyEncryptedWithOnlyItsLastCharactersInTheClear() {
        modelServerAccepts();
        when(userLlmKeyRepository.find(USER_ID)).thenReturn(Optional.of(stored(USER_ID)));

        userLlmKeyService.register(USER_ID, "  " + USER_KEY + " ", ADMIN_ID);

        ArgumentCaptor<byte[]> encryptedCaptor = ArgumentCaptor.captor();
        verify(userLlmKeyRepository).save(eq(USER_ID), encryptedCaptor.capture(), eq("1234"), eq(ADMIN_ID));

        byte[] encrypted = encryptedCaptor.getValue();

        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1)).doesNotContain(USER_KEY);
        assertThat(new String(bytesEncryptor.decrypt(encrypted), StandardCharsets.UTF_8)).isEqualTo(USER_KEY);
    }

    /**
     * The key is checked by asking the model server for its model list with it, which is the cheapest
     * request the server has to authorise.
     */
    @Test
    void checksTheKeyAgainstTheModelServerBeforeStoringIt() {
        modelServerAccepts();
        when(userLlmKeyRepository.find(USER_ID)).thenReturn(Optional.of(stored(USER_ID)));

        userLlmKeyService.register(USER_ID, USER_KEY, ADMIN_ID);

        ArgumentCaptor<ModelListParams> paramsCaptor = ArgumentCaptor.captor();
        verify(modelService).list(paramsCaptor.capture());

        assertThat(paramsCaptor.getValue()._additionalHeaders().values("Authorization"))
                .containsExactly("Bearer " + USER_KEY);
    }

    @Test
    void refusesAKeyTheModelServerRejects() {
        when(openAiClient.models()).thenReturn(modelService);
        when(modelService.list(any(ModelListParams.class)))
                .thenThrow(UnauthorizedException.builder().headers(Headers.builder().build()).build());

        assertThatThrownBy(() -> userLlmKeyService.register(USER_ID, USER_KEY, ADMIN_ID))
                .isInstanceOfSatisfying(LlmKeyRejectedException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmKeyErrorCode.INVALID_LLM_KEY));

        verify(userLlmKeyRepository, never()).save(any(), any(), anyString(), any());
    }

    @Test
    void storesNothingWhenTheKeyCannotBeChecked() {
        when(openAiClient.models()).thenReturn(modelService);
        when(modelService.list(any(ModelListParams.class))).thenThrow(new OpenAIIoException("connection refused"));

        assertThatThrownBy(() -> userLlmKeyService.register(USER_ID, USER_KEY, ADMIN_ID))
                .isInstanceOfSatisfying(LlmKeyRejectedException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmKeyErrorCode.LLM_KEY_VALIDATION_UNAVAILABLE));

        verify(userLlmKeyRepository, never()).save(any(), any(), anyString(), any());
    }

    /**
     * The shared key assigned to a user would be indistinguishable from no key at all, and every call
     * made with it would be refused as a call made for nobody.
     */
    @Test
    void refusesTheSharedServiceKey() {
        assertThatThrownBy(() -> userLlmKeyService.register(USER_ID, SERVICE_KEY, ADMIN_ID))
                .isInstanceOfSatisfying(LlmKeyRejectedException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmKeyErrorCode.INVALID_LLM_KEY));

        verifyNoInteractions(openAiClient, userLlmKeyRepository);
    }

    @Test
    void refusesAMalformedKeyWithoutAskingTheModelServer() {
        for (String malformed : List.of("", "short", "sk-has a-space-inside", "sk-" + "x".repeat(UserLlmKeyService.MAX_KEY_CHARACTERS))) {
            assertThatThrownBy(() -> userLlmKeyService.register(USER_ID, malformed, ADMIN_ID))
                    .isInstanceOf(LlmKeyRejectedException.class);
        }

        assertThatThrownBy(() -> userLlmKeyService.register(USER_ID, null, ADMIN_ID))
                .isInstanceOf(LlmKeyRejectedException.class);

        verifyNoInteractions(openAiClient, userLlmKeyRepository);
    }

    @Test
    void readsBackTheKeyThatWasStored() {
        when(userLlmKeyRepository.findApiKey(USER_ID))
                .thenReturn(Optional.of(bytesEncryptor.encrypt(USER_KEY.getBytes(StandardCharsets.UTF_8))));

        assertThat(userLlmKeyService.requireKey(USER_ID)).isEqualTo(new LlmApiKey(USER_KEY));
    }

    @Test
    void aUserWithNoKeyHasNoModelAccess() {
        when(userLlmKeyRepository.findApiKey(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userLlmKeyService.requireKey(USER_ID)).isInstanceOf(LlmKeyMissingException.class);
        assertThatThrownBy(() -> userLlmKeyService.asUser(USER_ID, () -> "never run"))
                .isInstanceOf(LlmKeyMissingException.class);
    }

    @Test
    void runsWorkInsideTheUsersScope() {
        when(userLlmKeyRepository.findApiKey(USER_ID))
                .thenReturn(Optional.of(bytesEncryptor.encrypt(USER_KEY.getBytes(StandardCharsets.UTF_8))));

        Optional<LlmApiKey> keySeen = userLlmKeyService.asUser(USER_ID, LlmCredentialScope::userKey);

        assertThat(keySeen).contains(new LlmApiKey(USER_KEY));
        assertThat(LlmCredentialScope.userKey()).isEmpty();
    }

    @Test
    void listsKeysWithTheirOwnersUsernames() {
        UUID otherUserId = UUID.randomUUID();

        when(userLlmKeyRepository.findPage(20, 0)).thenReturn(List.of(stored(USER_ID), stored(otherUserId)));
        when(userLlmKeyRepository.count()).thenReturn(2L);
        when(userProfileService.usernames(List.of(USER_ID, otherUserId))).thenReturn(Map.of(USER_ID, "sam"));

        Page<UserLlmKeySummary> page = userLlmKeyService.list(PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(UserLlmKeySummary::username).containsExactly("sam", null);
        assertThat(page.getContent()).extracting(UserLlmKeySummary::keyHint).containsOnly("1234");
    }
}
