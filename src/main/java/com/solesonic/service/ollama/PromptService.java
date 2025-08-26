package com.solesonic.service.ollama;

import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.ollama.OllamaModelRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.tools.confluence.CreateConfluenceTools;
import com.solesonic.tools.jira.AssigneeJiraTools;
import com.solesonic.tools.jira.CreateJiraTools;
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
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
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
    private final OllamaModelRepository ollamaModelRepository;
    private final VectorStore vectorStore;
    private final CreateJiraTools createJiraTools;
    private final AssigneeJiraTools assigneeJiraTools;
    private final CreateConfluenceTools createConfluenceTools;

    @Value("classpath:prompts/tools_prompt.st")
    private Resource toolsPrompt;

    @Value("classpath:prompts/basic_prompt.st")
    private Resource basicPrompt;

    @Value("${spring.ai.similarity-threshold}")
    private Double defaultSimilarityThreshold;

    public PromptService(
            ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            UserRequestContext userRequestContext,
            OllamaModelRepository ollamaModelRepository,
            VectorStore vectorStore,
            CreateJiraTools createJiraTools,
            AssigneeJiraTools assigneeJiraTools,
            CreateConfluenceTools createConfluenceTools) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.userRequestContext = userRequestContext;
        this.ollamaModelRepository = ollamaModelRepository;
        this.vectorStore = vectorStore;
        this.createJiraTools = createJiraTools;
        this.assigneeJiraTools = assigneeJiraTools;
        this.createConfluenceTools = createConfluenceTools;
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

        ToolCallback[] tools = tools(chatMessage, model);

        log.info("Using tools prompt for chat id {} as user input indicates tools are needed", chatId);

        return chatClient.prompt(contextPrompt)
                .user(chatMessage)
//                .toolCallbacks(tools)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor())
                .options(ollamaOptions)
                .call()
                .content();
    }

    public Prompt buildTemplatePrompt(String chatMessage) {
        // Determine which prompt to use based on user input
        Resource promptToUse = shouldUseToolsPrompt(chatMessage) ? toolsPrompt : basicPrompt;

        PromptTemplate promptTemplate = new PromptTemplate(promptToUse);

        Map<String, Object> promptContext = Map.of(
                INPUT, chatMessage);

        return promptTemplate.create(promptContext);
    }

    /**
     * Determines if the tool prompt should be used based on the user input.
     * Checks for keywords related to Jira or Confluence.
     *
     * @param userInput the user's input message
     * @return true if tools prompt should be used, false otherwise
     */
    public boolean shouldUseToolsPrompt(String userInput) {
        if (StringUtils.isEmpty(userInput)) {
            return false;
        }

        String input = userInput.toLowerCase();

        // Jira-related keywords
        boolean containsJiraKeywords = input.contains("jira") ||
                input.contains("issue") ||
                input.contains("ticket") ||
                input.contains("bug") ||
                input.contains("task") ||
                input.contains("assign") ||
                input.contains("project");

        // Confluence-related keywords
        boolean containsConfluenceKeywords = input.contains("confluence") ||
                input.contains("page") ||
                input.contains("wiki") ||
                input.contains("document") ||
                input.contains("documentation") ||
                input.contains("knowledge base");

        return containsJiraKeywords || containsConfluenceKeywords;
    }

    public ToolCallback[] tools(String chatMessage, String model) {
        // Check if the model supports tools
        boolean modelSupportsTools = ollamaModelRepository.findByName(model)
                .map(OllamaModel::isTools)
                .orElse(false);

        // Check if user input indicates tools should be used
        boolean userNeedsTools = shouldUseToolsPrompt(chatMessage);

        // Only use tools if both conditions are met
        boolean useTools = modelSupportsTools && userNeedsTools;

        if (useTools) {
            return ToolCallbacks.from(createConfluenceTools);
        }

        return new ToolCallback[]{};
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
