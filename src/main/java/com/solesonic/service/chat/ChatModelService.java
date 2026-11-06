package com.solesonic.service.chat;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIException;
import com.openai.models.models.Model;
import com.openai.models.models.ModelListParams;
import com.solesonic.exception.chat.ChatModelException;
import com.solesonic.model.chat.model.AvailableModel;
import com.solesonic.model.chat.model.ChatModelOptions;
import com.solesonic.model.chat.model.ChatModelState;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.LiteLlmModelInfoClient.ModelDisplaySettings;
import com.solesonic.service.llm.UserLlmKeyService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static com.solesonic.config.openai.OpenAiClientConfig.OPENAI_CLIENT;
import static com.solesonic.model.chat.model.ChatModelErrorCode.MODEL_LIST_UNAVAILABLE;
import static com.solesonic.model.chat.model.ChatModelErrorCode.UNKNOWN_MODEL;

/**
 * Chooses the chat model a conversation uses. Every list and every validation asks the endpoint
 * live, with the user's own key, so a user is offered exactly the models their key may use; nothing
 * about it is kept.
 * <p>
 * Ownership is the caller's to check: this service takes a chat id on trust.
 */
@Service
public class ChatModelService {
    private static final Logger log = LoggerFactory.getLogger(ChatModelService.class);

    private final OpenAIClient openAiClient;
    private final ChatModelSelection chatModelSelection;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final UserLlmKeyService userLlmKeyService;
    private final LiteLlmModelInfoClient liteLlmModelInfoClient;
    private final String defaultModel;

    public ChatModelService(@Qualifier(OPENAI_CLIENT) OpenAIClient openAiClient,
                            ChatModelSelection chatModelSelection,
                            A2AStickyAgentService a2aStickyAgentService,
                            UserLlmKeyService userLlmKeyService,
                            LiteLlmModelInfoClient liteLlmModelInfoClient,
                            @Value("${spring.ai.openai.model}") String defaultModel) {
        this.openAiClient = openAiClient;
        this.chatModelSelection = chatModelSelection;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.userLlmKeyService = userLlmKeyService;
        this.liteLlmModelInfoClient = liteLlmModelInfoClient;
        this.defaultModel = defaultModel;
    }

    /**
     * The options for a chat that does not exist yet: nothing is selected and no agent holds it.
     */
    public ChatModelOptions options(UUID userId) {
        return new ChatModelOptions(displayedModels(userId), defaultModel, null, null);
    }

    public ChatModelOptions options(UUID userId, UUID chatId) {
        return ChatModelOptions.of(displayedModels(userId), state(chatId));
    }

    /**
     * Stores the endpoint's spelling of the model, which is the one a completion request has to carry.
     */
    public ChatModelState select(UUID userId, UUID chatId, String requestedModel) {
        String model = resolve(userId, requestedModel);

        chatModelSelection.set(chatId, model).block();

        log.info("Chat {} switched to model {}", chatId, model);

        return state(chatId);
    }

    /**
     * The chat's selected model when {@code userId}'s own key cannot use it, otherwise empty. A shared chat's
     * model is chosen by any participant but every message runs on its sender's key, so before a run starts the
     * sender is checked rather than the run being left to fail at the provider. A chat on the default model is
     * not checked: nobody chose it, and a key that cannot use the server's default is a different problem. When
     * the model list cannot be fetched the answer is "usable" (and a warning), because a send is not worth
     * refusing on a guess.
     */
    public Optional<String> unusableSelectedModel(UUID userId, UUID chatId) {
        String selectedModel = chatModelSelection.get(chatId)
                .onErrorResume(exception -> {
                    log.warn("Could not read the model selected for chat {}: {}", chatId, exception.getMessage());

                    return Mono.empty();
                })
                .blockOptional()
                .orElse(null);

        if (selectedModel == null) {
            return Optional.empty();
        }

        try {
            boolean usable = availableModels(userId).stream().anyMatch(model -> model.id().equalsIgnoreCase(selectedModel));

            return usable ? Optional.empty() : Optional.of(selectedModel);
        } catch (ChatModelException exception) {
            log.warn("Could not check whether the key of user {} can use {}: {}", userId, selectedModel, exception.getErrorCode());

            return Optional.empty();
        }
    }

    public void reset(UUID chatId) {
        chatModelSelection.clear(chatId).block();

        log.info("Chat {} reset to the default model", chatId);
    }

    /**
     * An exact, case-insensitive match against the live list. {@code /} and {@code :} are ordinary
     * characters in a model id, so the name is never split or normalised.
     *
     * @return the endpoint's own spelling of the model
     */
    public String resolve(UUID userId, String requestedModel) {
        String trimmedModel = StringUtils.trimToEmpty(requestedModel);

        if (trimmedModel.isEmpty()) {
            throw new ChatModelException(UNKNOWN_MODEL, "A model name is required.");
        }

        return availableModels(userId).stream()
                .map(AvailableModel::id)
                .filter(modelId -> modelId.equalsIgnoreCase(trimmedModel))
                .findFirst()
                .orElseThrow(() -> new ChatModelException(UNKNOWN_MODEL,
                        "The model " + trimmedModel + " is not offered."));
    }

    /**
     * A selection or agent that cannot be read is reported as absent, which is what
     * {@code PromptService} falls back to in the same case.
     */
    public ChatModelState state(UUID chatId) {
        String selectedModel = chatModelSelection.get(chatId)
                .onErrorResume(exception -> {
                    log.warn("Could not read the model selected for chat {}: {}", chatId, exception.getMessage());

                    return Mono.empty();
                })
                .blockOptional()
                .orElse(null);

        String activeAgent = a2aStickyAgentService.getActiveAgent(chatId)
                .onErrorResume(exception -> {
                    log.warn("Could not read the sticky agent for chat {}: {}", chatId, exception.getMessage());

                    return Mono.empty();
                })
                .blockOptional()
                .flatMap(Function.identity())
                .orElse(null);

        return new ChatModelState(defaultModel, selectedModel, activeAgent);
    }

    /**
     * The live list with each model's display settings from LiteLLM's {@code model_info}. Only a picker
     * needs them, so validation ({@link #resolve}) asks for the bare list. A model with no settings, or
     * any model when they cannot be read, shows under its id and is not hidden.
     */
    private List<AvailableModel> displayedModels(UUID userId) {
        LlmApiKey llmApiKey = userLlmKeyService.requireKey(userId);
        List<AvailableModel> models = availableModels(llmApiKey);

        Map<String, ModelDisplaySettings> settingsByName = liteLlmModelInfoClient.displaySettings(llmApiKey);

        if (settingsByName.isEmpty()) {
            return models.stream()
                    .sorted(Comparator.comparing(AvailableModel::displayName))
                    .toList();
        }

        return models.stream()
                .map(model -> withDisplaySettings(model, settingsByName.get(model.id().toLowerCase(Locale.ROOT))))
                .sorted(Comparator.comparing(AvailableModel::displayName))
                .toList();
    }

    private static AvailableModel withDisplaySettings(AvailableModel model, ModelDisplaySettings settings) {
        if (settings == null) {
            return model;
        }

        String displayName = settings.displayName() != null ? settings.displayName() : model.id();

        return new AvailableModel(model.id(), model.ownedBy(), displayName, settings.hidden());
    }

    /**
     * @throws com.solesonic.exception.llm.LlmKeyMissingException when the user has no key
     */
    private List<AvailableModel> availableModels(UUID userId) {
        return availableModels(userLlmKeyService.requireKey(userId));
    }

    private List<AvailableModel> availableModels(LlmApiKey llmApiKey) {
        ModelListParams modelListParams = ModelListParams.builder()
                .putAdditionalHeader(HttpHeaders.AUTHORIZATION, llmApiKey.bearer())
                .build();

        try {
            return openAiClient.models()
                    .list(modelListParams)
                    .data()
                    .stream()
                    .map(ChatModelService::availableModel)
                    .toList();
        } catch (OpenAIException openAiException) {
            log.warn("Could not retrieve the model list: {}", openAiException.getMessage(), openAiException);

            throw new ChatModelException(MODEL_LIST_UNAVAILABLE, "The model list could not be retrieved.");
        }
    }

    /**
     * {@code owned_by} is read leniently: it is required by OpenAI's schema, and an OpenAI-compatible
     * server that leaves it out should not cost the user the whole list.
     */
    private static AvailableModel availableModel(Model model) {
        return new AvailableModel(model.id(), model._ownedBy().asKnown().orElse(null));
    }
}
