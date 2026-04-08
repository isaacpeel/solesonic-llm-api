
package com.solesonic.service.prompt;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.user.UserPreferencesService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.parameters.P;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;

import static com.solesonic.config.olllama.ChatConfig.DEFAULT_CHAT_CLIENT;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;
import static com.solesonic.model.prompt.SlashCommand.*;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    public static final String CHAT_ID = "chatId";
    public static final String BASIC_PROMPT = "basic-prompt";
    public static final String AGENT_NAME = "agentName";
    public static final String USER_MESSAGE = "userMessage";
    public static final String DEFAULT = "default";
    public static final String TASK_PROMPT = "task-prompt";
    public static final String TASK_TOOL = "taskTool";

    private final ChatClient chatClient;
    private final UserPreferencesService userPreferencesService;
    private final SlashCommandService slashCommandService;
    private final VectorStoreService vectorStoreService;
    private final McpSyncClient mcpClient;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    public PromptService(
            @Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            SlashCommandService slashCommandService,
            VectorStoreService vectorStoreService,
            McpSyncClient mcpClient) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.slashCommandService = slashCommandService;
        this.vectorStoreService = vectorStoreService;
        this.mcpClient = mcpClient;
    }

    public String model(UUID userId) {
        return userPreferencesService.get(userId).getModel();
    }

    public Flux<String> stream(UUID chatId, UUID userId, ChatRequest chatMessage, Authentication authentication) {
        log.info("Streaming prompt for chat id {}", chatId);
        String model = model(userId);
        String message = chatMessage.chatMessage();
        Set<String> commands = chatMessage.commands();

        if (CollectionUtils.isEmpty(commands)) {
            commands = Set.of(DEFAULT);
        }

        List<SlashCommand> slashCommands = slashCommandService.commands(commands);

        OllamaChatOptions ollamaChatOptions = OllamaChatOptions.builder()
                .model(model)
                .build();

        Advisor retrievalAugmentationAdvisor = vectorStoreService.retrievalAugmentationAdvisor(userId);

        Object principal = authentication.getPrincipal();

        String authToken = null;

        if (principal instanceof Jwt jwt) {
            authToken = jwt.getTokenValue();
        }

        assert authToken != null;
        Map<String, Object> contextMap = Map.of(USER_TOKEN, authToken, CHAT_ID, chatId);

        SlashCommand slashCommand = slashCommands.stream().findFirst()
                .orElseThrow(IllegalStateException::new);

        switch (slashCommand.commandType) {
            case TOOL -> {
                log.info("Tool invoke: {}", slashCommand.command);
                McpSchema.Tool tool = slashCommand.tool();

                McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest(
                        TASK_PROMPT,
                        Map.of(USER_MESSAGE, message, TASK_TOOL, tool.name())
                );

                McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);

                Prompt prompt = slashCommand.preparePrompt(getPromptResult, message);

                ChatClient taskClient = slashCommandService.taskClient(tool.name());

                var chatClientBuilder = taskClient.prompt(prompt)
                        .advisors(advisorSpec -> advisorSpec
                                .param(CONVERSATION_ID, chatId)
                        )
                        .advisors(retrievalAugmentationAdvisor)
                        .toolContext(contextMap);

                return Flux.deferContextual(_ ->
                        chatClientBuilder.stream()
                                .content());
            }
            case PROMPT -> {
                log.info("Prompt invoke: {}", slashCommand.name());
                var chatClientBuilder = chatClient.prompt(slashCommand.prompt())
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
            default -> throw new IllegalStateException();
        }
    }
}