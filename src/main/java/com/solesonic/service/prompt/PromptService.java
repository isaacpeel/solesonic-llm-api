
package com.solesonic.service.prompt;

import com.solesonic.mcp.client.prompt.McpPromptAdapter;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.user.UserPreferencesService;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
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
    public static final String USER_MESSAGE = "userMessage";
    public static final String DEFAULT = "default";
    public static final String TASK_PROMPT = "task-prompt";
    public static final String TASK_TOOL = "taskTool";
    public static final String PROGRESS_TOKEN = "progressToken";
    public static final String AGENT_NAME = "agentName";

    private final ChatClient chatClient;
    private final UserPreferencesService userPreferencesService;
    private final SlashCommandService slashCommandService;
    private final VectorStoreService vectorStoreService;
    private final McpAsyncClient mcpClient;
    private final McpPromptAdapter mcpPromptAdapter;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    public PromptService(
            @Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            SlashCommandService slashCommandService,
            VectorStoreService vectorStoreService,
            McpAsyncClient mcpClient,
            McpPromptAdapter mcpPromptAdapter) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.slashCommandService = slashCommandService;
        this.vectorStoreService = vectorStoreService;
        this.mcpClient = mcpClient;
        this.mcpPromptAdapter = mcpPromptAdapter;
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
            log.info("Using default command.");
            commands = Set.of(DEFAULT);
        }

        List<SlashCommand> slashCommands = slashCommandService.commands(commands);

        OllamaChatOptions ollamaChatOptions = OllamaChatOptions.builder()
                .model(model)
                .build();

        Advisor retrievalAugmentationAdvisor = vectorStoreService.retrievalAugmentationAdvisor(userId);

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof Jwt jwt)) {
            throw new IllegalStateException("Authentication principal is not a JWT token");
        }

        String authToken = jwt.getTokenValue();
        Map<String, Object> contextMap = Map.of(
                USER_TOKEN, authToken,
                CHAT_ID, chatId,
                PROGRESS_TOKEN, chatId);

        SlashCommand slashCommand = slashCommands.stream()
                .findFirst()
                .orElseThrow(IllegalStateException::new);

        switch (slashCommand.commandType) {
            case TOOL -> {
                log.info("Tool invoke: {}", slashCommand.command);
                McpSchema.Tool tool = slashCommand.tool();

                McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest(
                        TASK_PROMPT,
                        Map.of(USER_MESSAGE, message, TASK_TOOL, tool.name())
                );

                return mcpClient.getPrompt(getPromptRequest)
                        .map(mcpPromptAdapter::toPrompt)
                        .flatMapMany(prompt ->
                                slashCommandService.taskClient(tool.name())
                                        .flatMapMany(taskClient -> taskClient.prompt(prompt)
                                                .user(message)
                                                .advisors(advisorSpec -> advisorSpec
                                                        .param(CONVERSATION_ID, chatId)
                                                )
                                                .advisors(retrievalAugmentationAdvisor)
                                                .toolContext(contextMap)
                                                .stream()
                                                .content()
                                        )
                        );
            }
            case PROMPT -> {
                log.info("Prompt invoke: {}", slashCommand.name());

                McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest(
                        slashCommand.name(),
                        Map.of(USER_MESSAGE, message, AGENT_NAME, agentName)
                );

                return mcpClient.getPrompt(getPromptRequest)
                        .flatMapMany(getPromptResult -> {
                            org.springframework.ai.chat.prompt.Prompt prompt = slashCommand.preparePrompt(getPromptResult, message);

                            return chatClient.prompt(prompt)
                                    .advisors(advisorSpec -> advisorSpec
                                            .param(CONVERSATION_ID, chatId)
                                    )
                                    .advisors(retrievalAugmentationAdvisor)
                                    .toolContext(contextMap)
                                    .options(ollamaChatOptions)
                                    .stream()
                                    .content();
                        });
            }
            default -> throw new IllegalStateException();
        }
    }
}