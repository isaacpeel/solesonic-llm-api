
package com.solesonic.service.ollama;

import com.solesonic.model.user.UserPreferences;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.intent.IntentType;
import com.solesonic.service.intent.UserIntentService;
import com.solesonic.service.user.UserPreferencesService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;
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
    private final List<McpSyncClient> mcpSyncClients;

    @Value("classpath:prompts/jira_prompt.st")
    private Resource jiraPrompt;

    @Value("classpath:prompts/confluence_prompt.st")
    private Resource confluencePrompt;

    @Value("classpath:prompts/basic_prompt.st")
    private Resource basicPrompt;

    @Value("classpath:prompts/agile_prompt.st")
    private Resource agilePrompt;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    @Value("${spring.ai.similarity-threshold}")
    private Double defaultSimilarityThreshold;

    public PromptService(
            ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            UserRequestContext userRequestContext,
            VectorStore vectorStore,
            UserIntentService userIntentService,
            List<McpSyncClient> mcpSyncClients) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.userRequestContext = userRequestContext;
        this.vectorStore = vectorStore;
        this.userIntentService = userIntentService;
        this.mcpSyncClients = mcpSyncClients;
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

        OllamaChatOptions ollamaChatOptions = OllamaChatOptions.builder()
                .model(model)
                .build();

        Advisor retrievalAugmentationAdvisor = retrievalAugmentationAdvisor();

        // Build the chat client call
        var chatClientBuilder = chatClient.prompt(templatePrompt)
                .user(chatMessage)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .options(ollamaChatOptions);

        return chatClientBuilder.call().content();
    }

    public Flux<String> stream(UUID chatId, String chatMessage) {
        log.info("Streaming prompt for chat id {}", chatId);
        String model = model();

        McpSyncClient mcpSyncClient = mcpSyncClients.getFirst();

        McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest("basic-prompt", Map.of("agentName", agentName, "userMessage", chatMessage));

        McpSchema.GetPromptResult getPromptResult = mcpSyncClient.getPrompt(getPromptRequest);

        List<Message> messages = getPromptResult.messages().stream()
                .map(mcpMessage -> {
                    if (mcpMessage instanceof McpSchema.PromptMessage(McpSchema.Role role, McpSchema.Content content)) {
                        String textContent = extractTextContent(content);

                        return (Message) switch (role) {
                            case USER -> new org.springframework.ai.chat.messages.UserMessage(textContent);
                            case ASSISTANT -> new org.springframework.ai.chat.messages.AssistantMessage(textContent);
                        };

                        }
                    throw new IllegalArgumentException("Unexpected message type.");
                })
                .toList();

        Prompt mcpPrompt = new Prompt(messages);

        OllamaChatOptions ollamaChatOptions = OllamaChatOptions.builder()
                .model(model)
                .build();

        Advisor retrievalAugmentationAdvisor = retrievalAugmentationAdvisor();

        SecurityContext securityContext = SecurityContextHolder.getContext();
        Authentication authentication = securityContext.getAuthentication();
        Object principal = authentication.getPrincipal();

        String authToken = null;

        if(principal instanceof Jwt jwt) {
            authToken = jwt.getTokenValue();
        }

        assert authToken != null;
        Map<String, Object> contextMap = Map.of(USER_TOKEN, authToken);

        var chatClientBuilder = chatClient.prompt(mcpPrompt)
                .user(chatMessage)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .toolContext(contextMap)
                .options(ollamaChatOptions);

        return Flux.deferContextual(upstreamView ->
                chatClientBuilder.stream()
                        .content());
    }

    private String extractTextContent(Object content) {
        if (content instanceof String) {
            return (String) content;
        } else if (content instanceof McpSchema.TextContent textContent) {
            return textContent.text();
        } else if (content instanceof McpSchema.ImageContent) {
            return "[Image content not supported]";
        } else if (content instanceof List<?> contentList) {
            // Handle list of content items
            return contentList.stream()
                    .map(item -> {
                        if (item instanceof McpSchema.TextContent textContent) {
                            return textContent.text();
                        } else if (item instanceof String) {
                            return (String) item;
                        }
                        return "";
                    })
                    .filter(s -> !s.isEmpty())
                    .reduce("", (a, b) -> a + "\n" + b);
        }
        return "";
    }

    public Prompt buildTemplatePrompt(String chatMessage, Resource promptToUse) {
        PromptTemplate promptTemplate = new PromptTemplate(promptToUse);

        Map<String, Object> promptContext = Map.of(
                INPUT, chatMessage,
                BOT_NAME, agentName,
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
            case JIRA_AGILE -> agilePrompt;
            case GENERAL -> basicPrompt;
        };
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