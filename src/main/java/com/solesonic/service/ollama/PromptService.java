package com.solesonic.service.ollama;

import com.solesonic.model.user.UserPreferences;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.intent.IntentType;
import com.solesonic.service.intent.UserIntentService;
import com.solesonic.service.user.UserPreferencesService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    public static final String INPUT = "input";

    private final ChatClient chatClient;
    private final UserPreferencesService userPreferencesService;
    private final UserRequestContext userRequestContext;
    private final VectorStore vectorStore;
    private final UserIntentService userIntentService;

    @Value("classpath:prompts/jira_prompt.st")
    private Resource jiraPrompt;

    @Value("classpath:prompts/confluence_prompt.st")
    private Resource confluencePrompt;

    @Value("classpath:prompts/basic_prompt.st")
    private Resource basicPrompt;

    @Value("${spring.ai.similarity-threshold}")
    private Double defaultSimilarityThreshold;

    public PromptService(
            ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            UserRequestContext userRequestContext,
            VectorStore vectorStore,
            UserIntentService userIntentService) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.userRequestContext = userRequestContext;
        this.vectorStore = vectorStore;
        this.userIntentService = userIntentService;
    }

    public String getModel() {
        UUID userId = userRequestContext.getUserId();
        UserPreferences userPreferences = userPreferencesService.get(userId);
        String model = userPreferences.getModel();
        userRequestContext.setChatModel(model);

        return model;
    }

    private UserPreferences getUserPreferences() {
        UUID userId = userRequestContext.getUserId();
        return userPreferencesService.get(userId);
    }

    public String prompt(UUID chatId,
                      String chatMessage,
                      Prompt contextPrompt) {

        String model = getModel();

        OllamaOptions ollamaOptions = OllamaOptions.builder()
                .model(model)
                .build();

        Advisor retrievalAugmentationAdvisor = retrievalAugmentationAdvisor();

        return chatClient.prompt(contextPrompt)
                .user(chatMessage)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .options(ollamaOptions)
                .call()
                .content();
    }

    public Prompt buildTemplatePrompt(String chatMessage) {
        // Determine which prompt to use based on user input
        Resource promptToUse = determinePromptToUse(chatMessage);

        PromptTemplate promptTemplate = new PromptTemplate(promptToUse);

        Map<String, Object> promptContext = Map.of(INPUT, chatMessage);

        return promptTemplate.create(promptContext);
    }

    /**
     * Determines which prompt template to use based on user intent detection.
     * 
     * @param userInput the user's input message
     * @return the appropriate prompt resource
     */
    private Resource determinePromptToUse(String userInput) {
        if (StringUtils.isEmpty(userInput)) {
            return basicPrompt;
        }

        IntentType intent = userIntentService.determineIntent(userInput);
        
        log.debug("Determined intent '{}' for user input: '{}'", intent, userInput);

        return switch (intent) {
            case CREATING_JIRA_ISSUE -> jiraPrompt;
            case CREATING_CONFLUENCE_PAGE -> confluencePrompt;
            case GENERAL -> basicPrompt;
        };
    }

    private Advisor retrievalAugmentationAdvisor() {
        Double similarityThreshold = Optional.ofNullable(getUserPreferences().getSimilarityThreshold())
                .orElse(defaultSimilarityThreshold);

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(similarityThreshold)
                        .vectorStore(vectorStore)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .build();
    }
}
