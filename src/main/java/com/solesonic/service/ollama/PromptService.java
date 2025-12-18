
package com.solesonic.service.ollama;

import com.solesonic.model.user.UserPreferences;
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
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
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
    public static final String CHAT_ID = "chatId";
    public static final String BASIC_PROMPT = "basic-prompt";
    public static final String AGENT_NAME = "agentName";
    public static final String USER_MESSAGE = "userMessage";

    private final ChatClient chatClient;
    private final UserPreferencesService userPreferencesService;
    private final VectorStore vectorStore;
    private final UserIntentService userIntentService;
    private final List<McpSyncClient> mcpSyncClients;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    @Value("${spring.ai.similarity-threshold}")
    private Double defaultSimilarityThreshold;

    public PromptService(
            ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            VectorStore vectorStore,
            UserIntentService userIntentService,
            List<McpSyncClient> mcpSyncClients) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.vectorStore = vectorStore;
        this.userIntentService = userIntentService;
        this.mcpSyncClients = mcpSyncClients;
    }

    public String model(UUID userId) {
        UserPreferences userPreferences = userPreferencesService.get(userId);

        return userPreferences.getModel();
    }

    private UserPreferences userPreferences(UUID userId) {
        return userPreferencesService.get(userId);
    }

    public String prompt(UUID chatId, UUID userId, String chatMessage) {
        String model = model(userId);

        String chosenPromptId;

        try {
            Prompt prompt = userIntentService.prompt(chatMessage);

            String routingResponse = chatClient
                    .prompt(prompt)
                    .call()
                    .content();

            if (routingResponse == null || routingResponse.trim().isEmpty()) {
                log.warn("Intent model returned empty response; falling back to basic-prompt");

                chosenPromptId = BASIC_PROMPT;

            } else {
                chosenPromptId = routingResponse.trim();
            }

            log.debug("Chosen MCP prompt id '{}' for user message '{}'", chosenPromptId, chatMessage);

        } catch (Exception exception) {
            log.error("Failed to obtain routing decision from intent model: {}", exception.getMessage(), exception);

            chosenPromptId = BASIC_PROMPT;
        }

        McpSyncClient mcpSyncClient = mcpSyncClients.getFirst();

        McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest(
                chosenPromptId,
                Map.of(AGENT_NAME, agentName, USER_MESSAGE, chatMessage)
        );

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

        Advisor retrievalAugmentationAdvisor = retrievalAugmentationAdvisor(userId);

        // Build the chat client call
        var chatClientBuilder = chatClient.prompt(mcpPrompt)
                .user(chatMessage)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .options(ollamaChatOptions);

        return chatClientBuilder.call().content();
    }

    public Flux<String> stream(UUID chatId, UUID userId, String chatMessage, Authentication authentication) {
        log.info("Streaming prompt for chat id {}", chatId);
        String model = model(userId);

        String promptName = userIntentService.determineIntent(chatMessage);

        log.info("Intent model chose '{}'", promptName);

        McpSyncClient mcpSyncClient = mcpSyncClients.getFirst();

        McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest(
                promptName,
                Map.of(AGENT_NAME, agentName, USER_MESSAGE, chatMessage)
        );

        McpSchema.GetPromptResult getPromptResult = mcpSyncClient.getPrompt(getPromptRequest);

        if (getPromptResult.messages().isEmpty()) {
            getPromptRequest = new McpSchema.GetPromptRequest(
                    promptName,
                    Map.of(AGENT_NAME, BASIC_PROMPT, USER_MESSAGE, chatMessage)
            );

            getPromptResult = mcpSyncClient.getPrompt(getPromptRequest);
        }

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

        Advisor retrievalAugmentationAdvisor = retrievalAugmentationAdvisor(userId);

        Object principal = authentication.getPrincipal();

        String authToken = null;

        if (principal instanceof Jwt jwt) {
            authToken = jwt.getTokenValue();
        }

        assert authToken != null;
        Map<String, Object> contextMap = Map.of(USER_TOKEN, authToken, CHAT_ID, chatId);

        var chatClientBuilder = chatClient.prompt(mcpPrompt)
                .user(chatMessage)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .toolContext(contextMap)
                .options(ollamaChatOptions);

        return Flux.deferContextual(_ ->
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

    private Advisor retrievalAugmentationAdvisor(UUID userId) {
        Double similarityThreshold = Optional.ofNullable(userPreferences(userId).getSimilarityThreshold())
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