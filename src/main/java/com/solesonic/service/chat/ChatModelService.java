package com.solesonic.service.chat;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIException;
import com.openai.models.models.Model;
import com.solesonic.exception.chat.ChatModelException;
import com.solesonic.model.chat.model.AvailableModel;
import com.solesonic.model.chat.model.ChatModelOptions;
import com.solesonic.model.chat.model.ChatModelState;
import com.solesonic.service.a2a.A2AStickyAgentService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.solesonic.config.openai.OpenAiClientConfig.OPENAI_CLIENT;
import static com.solesonic.model.chat.model.ChatModelErrorCode.MODEL_LIST_UNAVAILABLE;
import static com.solesonic.model.chat.model.ChatModelErrorCode.UNKNOWN_MODEL;

/**
 * Chooses the chat model a conversation uses. Every list and every validation asks the endpoint
 * live, with the chat's own credentials; nothing about it is kept.
 * <p>
 * Ownership is the caller's to check: this service takes a chat id on trust.
 */
@Service
public class ChatModelService {
    private static final Logger log = LoggerFactory.getLogger(ChatModelService.class);

    private final OpenAIClient openAiClient;
    private final ChatModelSelection chatModelSelection;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final String defaultModel;

    public ChatModelService(@Qualifier(OPENAI_CLIENT) OpenAIClient openAiClient,
                            ChatModelSelection chatModelSelection,
                            A2AStickyAgentService a2aStickyAgentService,
                            @Value("${spring.ai.openai.model}") String defaultModel) {
        this.openAiClient = openAiClient;
        this.chatModelSelection = chatModelSelection;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.defaultModel = defaultModel;
    }

    /**
     * The options for a chat that does not exist yet: nothing is selected and no agent holds it.
     */
    public ChatModelOptions options() {
        return new ChatModelOptions(availableModels(), defaultModel, null, null);
    }

    public ChatModelOptions options(UUID chatId) {
        return ChatModelOptions.of(availableModels(), state(chatId));
    }

    /**
     * Stores the endpoint's spelling of the model, which is the one a completion request has to carry.
     */
    public ChatModelState select(UUID chatId, String requestedModel) {
        String model = resolve(requestedModel);

        chatModelSelection.set(chatId, model).block();

        log.info("Chat {} switched to model {}", chatId, model);

        return state(chatId);
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
    public String resolve(String requestedModel) {
        String trimmedModel = StringUtils.trimToEmpty(requestedModel);

        if (trimmedModel.isEmpty()) {
            throw new ChatModelException(UNKNOWN_MODEL, "A model name is required.");
        }

        return availableModels().stream()
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

    private List<AvailableModel> availableModels() {
        try {
            return openAiClient.models()
                    .list()
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
