package com.solesonic.service.ollama;

import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.ollama.OllamaModelRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.intent.IntentType;
import com.solesonic.service.intent.UserIntentService;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.tools.confluence.CreateConfluenceTools;
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
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.ollama.OllamaService.CAPABILITIES;
import static com.solesonic.service.ollama.OllamaService.TOOLS;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    public static final String INPUT = "input";
    public static final String BOT_NAME = "botName";
    public static final String LBRACE = "lbrace";
    public static final String RBRACE = "rbrace";

    private final ChatClient chatClient;
    private final UserPreferencesService userPreferencesService;
    private final UserRequestContext userRequestContext;
    private final VectorStore vectorStore;
    private final UserIntentService userIntentService;
    private final CreateConfluenceTools createConfluenceTools;
    private final OllamaModelRepository ollamaModelRepository;

    @Value("classpath:prompts/jira_prompt.st")
    private Resource jiraPrompt;

    @Value("classpath:prompts/confluence_prompt.st")
    private Resource confluencePrompt;

    @Value("classpath:prompts/basic_prompt.st")
    private Resource basicPrompt;

    @Value("${solesonic.llm.bot.name}")
    private String botName;

    @Value("${spring.ai.similarity-threshold}")
    private Double defaultSimilarityThreshold;

    public PromptService(
            ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            UserRequestContext userRequestContext,
            VectorStore vectorStore,
            UserIntentService userIntentService,
            CreateConfluenceTools createConfluenceTools,
            OllamaModelRepository ollamaModelRepository) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.userRequestContext = userRequestContext;
        this.vectorStore = vectorStore;
        this.userIntentService = userIntentService;
        this.createConfluenceTools = createConfluenceTools;
        this.ollamaModelRepository = ollamaModelRepository;
    }

    public String model() {
        UUID userId = userRequestContext.getUserId();
        UserPreferences userPreferences = userPreferencesService.get(userId);
        String model = userPreferences.getModel();
        userRequestContext.setChatModel(model);

        return model;
    }

    private UserPreferences userPreferences() {
        UUID userId = userRequestContext.getUserId();
        return userPreferencesService.get(userId);
    }

    public String prompt(UUID chatId, String chatMessage) {
        String model = model();

        IntentType intent = userIntentService.determineIntent(chatMessage);

        Resource promptTemplate = promptResource(intent);

        Prompt templatePrompt = buildTemplatePrompt(chatMessage, promptTemplate);

        ToolCallback[] toolCallbacks = tools(intent, model);

        OllamaOptions ollamaOptions = OllamaOptions.builder()
                .model(model)
                .build();

        Advisor retrievalAugmentationAdvisor = retrievalAugmentationAdvisor();

        // Build the chat client call
        var chatClientBuilder = chatClient.prompt(templatePrompt)
                .user(chatMessage)
                .toolCallbacks(toolCallbacks)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .options(ollamaOptions);

        return chatClientBuilder.call().content();
    }

    public Prompt buildTemplatePrompt(String chatMessage, Resource promptToUse) {
        PromptTemplate promptTemplate = new PromptTemplate(promptToUse);

        Map<String, Object> promptContext = Map.of(
                INPUT, chatMessage,
                BOT_NAME, botName,
                LBRACE, "{",
                RBRACE, "}");

        return promptTemplate.create(promptContext);
    }

    /**
     * Determines which prompt template to use based on resolved intent.
     *
     * @param intent the resolved user intent
     * @return the appropriate prompt resource
     */
    private Resource promptResource(IntentType intent) {
        return switch (intent) {
            case CREATING_JIRA_ISSUE -> jiraPrompt;
            case CREATING_CONFLUENCE_PAGE -> confluencePrompt;
            case GENERAL -> basicPrompt;
        };
    }

    /**
     * Determines which tools to provide based on resolved intent and model capabilities.
     *
     * @param intent the resolved user intent
     * @param model  the model name to check for tool support
     * @return array of tool callbacks, empty if the model doesn't support tools or intent doesn't require tools
     */
    public ToolCallback[] tools(IntentType intent, String model) {
        try {
            Optional<OllamaModel> modelOpt = ollamaModelRepository.findByName(model);

            if (modelOpt.isEmpty()) {
                log.debug("Model not found");
                return new ToolCallback[0];
            } else {
                OllamaModel ollamaModel = modelOpt.get();

                Map<String, Object> ollamaShow = ollamaModel.getOllamaShow();

                if(ollamaShow != null && ollamaShow.containsKey(CAPABILITIES)) {
                    @SuppressWarnings("unchecked")
                    List<String> capabilities = (List<String>) ollamaShow.get(CAPABILITIES);

                    if(!capabilities.contains(TOOLS)) {
                        log.debug("Model '{}' does not support tools", model);
                        return new ToolCallback[0];
                    }
                }
            }

            ToolCallback[] toolCallbacks = switch (intent) {
                case CREATING_JIRA_ISSUE, GENERAL ->  new ToolCallback[0];
                case CREATING_CONFLUENCE_PAGE -> ToolCallbacks.from(createConfluenceTools);
            };

            log.debug("Selected {} tools for intent '{}' and model '{}'", toolCallbacks.length, intent, model);
            return toolCallbacks;

        } catch (Exception e) {
            log.error("Error selecting tools for intent '{}' and model '{}': {}", intent, model, e.getMessage(), e);
            return new ToolCallback[0];
        }
    }

    private Advisor retrievalAugmentationAdvisor() {
        Double similarityThreshold = Optional.ofNullable(userPreferences().getSimilarityThreshold())
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
