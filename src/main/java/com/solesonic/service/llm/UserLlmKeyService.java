package com.solesonic.service.llm;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.models.ModelListParams;
import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.exception.llm.LlmKeyRejectedException;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.model.llm.UserLlmKeySummary;
import com.solesonic.repository.llm.UserLlmKeyRepository;
import com.solesonic.repository.llm.UserLlmKeyRepository.StoredLlmKey;
import com.solesonic.service.user.UserProfileService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.solesonic.config.openai.OpenAiClientConfig.OPENAI_CLIENT;
import static com.solesonic.model.llm.LlmKeyErrorCode.INVALID_LLM_KEY;
import static com.solesonic.model.llm.LlmKeyErrorCode.LLM_KEY_VALIDATION_UNAVAILABLE;

/**
 * Each user's own model-server key: every model call made for a user carries it, and a user without
 * one gets no model call at all.
 * <p>
 * Nothing here caches. The lookup is a primary-key read, so a key an administrator replaces or
 * removes takes effect on the user's next call on every instance.
 */
@Service
public class UserLlmKeyService {
    private static final Logger log = LoggerFactory.getLogger(UserLlmKeyService.class);

    static final int MIN_KEY_CHARACTERS = 12;
    static final int MAX_KEY_CHARACTERS = 512;

    private final UserLlmKeyRepository userLlmKeyRepository;
    private final BytesEncryptor bytesEncryptor;
    private final OpenAIClient openAiClient;
    private final UserProfileService userProfileService;
    private final LlmApiKey serviceApiKey;

    public UserLlmKeyService(UserLlmKeyRepository userLlmKeyRepository,
                             BytesEncryptor bytesEncryptor,
                             @Qualifier(OPENAI_CLIENT) OpenAIClient openAiClient,
                             UserProfileService userProfileService,
                             @Value("${spring.ai.openai.api-key}") String serviceApiKey) {
        this.userLlmKeyRepository = userLlmKeyRepository;
        this.bytesEncryptor = bytesEncryptor;
        this.openAiClient = openAiClient;
        this.userProfileService = userProfileService;
        this.serviceApiKey = new LlmApiKey(serviceApiKey);
    }

    public Optional<LlmApiKey> findKey(UUID userId) {
        return userLlmKeyRepository.findApiKey(userId)
                .map(encrypted -> new LlmApiKey(new String(bytesEncryptor.decrypt(encrypted), StandardCharsets.UTF_8)));
    }

    /**
     * @throws LlmKeyMissingException when the user has no key
     */
    public LlmApiKey requireKey(UUID userId) {
        return findKey(userId).orElseThrow(LlmKeyMissingException::new);
    }

    /**
     * Runs blocking model work on the user's key; see {@link LlmCredentialScope}.
     *
     * @throws LlmKeyMissingException when the user has no key, before any of the work runs
     */
    public <T> T asUser(UUID userId, Supplier<T> work) {
        return LlmCredentialScope.asUser(requireKey(userId), work);
    }

    public void runAsUser(UUID userId, Runnable work) {
        LlmCredentialScope.runAsUser(requireKey(userId), work);
    }

    /**
     * Stores a key once the model server has accepted it, replacing any the user had.
     *
     * @throws LlmKeyRejectedException when the key is malformed, is the shared service key, is refused
     *                                 by the model server, or cannot be checked
     */
    public UserLlmKeySummary register(UUID userId, String apiKey, UUID registeredBy) {
        LlmApiKey llmApiKey = wellFormed(apiKey);

        if (llmApiKey.equals(serviceApiKey)) {
            throw new LlmKeyRejectedException(INVALID_LLM_KEY, "The shared service key cannot be assigned to a user.");
        }

        verify(llmApiKey);

        userLlmKeyRepository.save(userId,
                bytesEncryptor.encrypt(llmApiKey.value().getBytes(StandardCharsets.UTF_8)),
                llmApiKey.hint(),
                registeredBy);

        log.info("Model access key registered for user {} by {}", userId, registeredBy);

        return summary(userId).orElseThrow();
    }

    public Optional<UserLlmKeySummary> summary(UUID userId) {
        return userLlmKeyRepository.find(userId)
                .map(storedLlmKey -> summary(storedLlmKey, userProfileService.username(userId).orElse(null)));
    }

    public Page<UserLlmKeySummary> list(Pageable pageable) {
        List<StoredLlmKey> storedLlmKeys = userLlmKeyRepository.findPage(pageable.getPageSize(), pageable.getOffset());

        Map<UUID, String> usernames = userProfileService.usernames(storedLlmKeys.stream().map(StoredLlmKey::userId).toList());

        List<UserLlmKeySummary> summaries = storedLlmKeys.stream()
                .map(storedLlmKey -> summary(storedLlmKey, usernames.get(storedLlmKey.userId())))
                .toList();

        return new PageImpl<>(summaries, pageable, userLlmKeyRepository.count());
    }

    /**
     * @return false when the user had no key
     */
    public boolean delete(UUID userId, UUID deletedBy) {
        boolean deleted = userLlmKeyRepository.delete(userId);

        if (deleted) {
            log.info("Model access key removed for user {} by {}", userId, deletedBy);
        }

        return deleted;
    }

    private static LlmApiKey wellFormed(String apiKey) {
        String trimmed = StringUtils.trimToEmpty(apiKey);

        if (trimmed.length() < MIN_KEY_CHARACTERS || trimmed.length() > MAX_KEY_CHARACTERS
                || trimmed.chars().anyMatch(character -> character <= ' ' || character > '~')) {
            throw new LlmKeyRejectedException(INVALID_LLM_KEY, "The key is not a well-formed API key.");
        }

        return new LlmApiKey(trimmed);
    }

    /**
     * Asks the model server for its model list with the key, which is the cheapest request it has to
     * authorise. Only what the server said is logged, never the key.
     */
    private void verify(LlmApiKey llmApiKey) {
        try {
            openAiClient.models().list(ModelListParams.builder()
                    .putAdditionalHeader(HttpHeaders.AUTHORIZATION, llmApiKey.bearer())
                    .build());
        } catch (UnauthorizedException | PermissionDeniedException rejected) {
            log.info("The model server refused a key being registered: {}", rejected.statusCode());

            throw new LlmKeyRejectedException(INVALID_LLM_KEY, "The model server did not accept this key.");
        } catch (OpenAIException openAiException) {
            log.warn("Could not check a key against the model server: {}", openAiException.getClass().getSimpleName());

            throw new LlmKeyRejectedException(LLM_KEY_VALIDATION_UNAVAILABLE,
                    "The key could not be checked against the model server. Please try again.");
        }
    }

    private static UserLlmKeySummary summary(StoredLlmKey storedLlmKey, String username) {
        return new UserLlmKeySummary(storedLlmKey.userId(), username, storedLlmKey.keyHint(),
                storedLlmKey.created(), storedLlmKey.updated(), storedLlmKey.updatedBy());
    }
}
