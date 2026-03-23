
package com.solesonic.service.prompt;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.prompt.SlashCommandPrompt;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.user.UserPreferencesService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
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

import java.util.*;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    public static final String CHAT_ID = "chatId";
    public static final String BASIC_PROMPT = "basic-prompt";
    public static final String AGENT_NAME = "agentName";
    public static final String USER_MESSAGE = "userMessage";
    public static final String DEFAULT = "default";

    private final ChatClient chatClient;
    private final UserPreferencesService userPreferencesService;
    private final VectorStore vectorStore;
    private final List<McpSyncClient> mcpSyncClients;
    private final SlashCommandService slashCommandService;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    @Value("${spring.ai.similarity-threshold}")
    private Double defaultSimilarityThreshold;

    private record PreparedPrompt(Prompt mcpPrompt, OllamaChatOptions options, Advisor retrievalAdvisor) {
    }

    public PromptService(
            ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            VectorStore vectorStore,
            List<McpSyncClient> mcpSyncClients,
            SlashCommandService slashCommandService) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.vectorStore = vectorStore;
        this.mcpSyncClients = mcpSyncClients;
        this.slashCommandService = slashCommandService;
    }

    public String model(UUID userId) {
        return userPreferencesService.get(userId).getModel();
    }

    private UserPreferences userPreferences(UUID userId) {
        return userPreferencesService.get(userId);
    }

    public Flux<String> stream(UUID chatId, UUID userId, ChatRequest chatMessage, Authentication authentication) {
        log.info("Streaming prompt for chat id {}", chatId);
        String model = model(userId);
        String message = chatMessage.chatMessage();
        Set<String> commands = chatMessage.commands();

        if (CollectionUtils.isEmpty(commands)) {
            commands = Set.of(DEFAULT);
        }

        SlashCommandPrompt slashCommandPrompt = slashCommandService.command(command);
        String promptName = slashCommandPrompt.name();
        log.info("Slash command prompt selected: {}", slashCommandPrompt.name());

        McpSchema.GetPromptResult getPromptResult = getGetPromptResult(message, promptName);

        PreparedPrompt prepared = preparePrompt(getPromptResult, model, userId);

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

        var chatClientBuilder = chatClient.prompt(prepared.mcpPrompt)
                .user(message)
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

    private McpSchema.GetPromptResult getGetPromptResult(String chatMessage, String promptName) {
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
        return getPromptResult;
    }

    private String extractTextContent(Object content) {
        return switch (content) {
            case String text -> text;
            case McpSchema.TextContent textContent -> textContent.text();
            case McpSchema.ImageContent _ -> "[Image content not supported]";
            case List<?> contentList -> contentList.stream()
                    .map(item -> switch (item) {
                        case McpSchema.TextContent textContent -> textContent.text();
                        case String text -> text;
                        default -> "";
                    })
                    .filter(entry -> !entry.isEmpty())
                    .reduce("", (accumulated, next) -> accumulated + "\n" + next);
            default -> "";
        };
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

    private PreparedPrompt preparePrompt(McpSchema.GetPromptResult getPromptResult, String model, UUID userId) {
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

        return new PreparedPrompt(mcpPrompt, ollamaChatOptions, retrievalAugmentationAdvisor);
    }
}