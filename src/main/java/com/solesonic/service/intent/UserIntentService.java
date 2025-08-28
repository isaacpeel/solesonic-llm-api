package com.solesonic.service.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UserIntentService {
    private static final Logger log = LoggerFactory.getLogger(UserIntentService.class);
    public static final String USER_MESSAGE = "user_message";

    private final OllamaApi ollamaApi;

    @Value("classpath:prompts/intent_prompt.st")
    private Resource intentPrompt;

    @Value("${soleonic.llm.intent.model}")
    private String intentModel;

    public UserIntentService(OllamaApi ollamaApi) {
        this.ollamaApi = ollamaApi;
    }


    /**
     * Determines the user's intent based on their message using LLM classification.
     *
     * @param userMessage the user's input message
     * @return the classified intent type
     */
    public IntentType determineIntent(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return IntentType.GENERAL;
        }

        try {
            PromptTemplate promptTemplate = new PromptTemplate(intentPrompt);
            Map<String, Object> promptContext = Map.of(USER_MESSAGE, userMessage);
            Prompt prompt = promptTemplate.create(promptContext);

            OllamaOptions ollamaOptions = OllamaOptions.builder()
                    .model(intentModel)
                    .build();

            ModelManagementOptions modelManagementOptions = ModelManagementOptions.builder()
                    .pullModelStrategy(PullModelStrategy.WHEN_MISSING)
                    .build();

            OllamaChatModel intentChatModel = OllamaChatModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(ollamaOptions)
                    .modelManagementOptions(modelManagementOptions)
                    .build();

            ChatClient intentChatClient = ChatClient.builder(intentChatModel)
                    .build();

            String response = intentChatClient.prompt(prompt)
                    .call()
                    .content();

            log.debug("Intent classification response for message '{}': '{}'", userMessage, response);

            return IntentType.fromLabel(response);

        } catch (Exception e) {
            log.error("Error determining intent for message '{}': {}", userMessage, e.getMessage(), e);
            return IntentType.GENERAL;
        }
    }
}
