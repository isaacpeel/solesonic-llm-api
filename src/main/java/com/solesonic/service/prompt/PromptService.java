package com.solesonic.service.prompt;

import com.solesonic.mcp.client.prompt.McpPromptAdapter;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.user.UserPreferencesService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.Prompt;
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
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    public static final String CHAT_ID = "chatId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String BASIC_PROMPT = "basic-prompt";
    public static final String PROGRESS_TOKEN = "progressToken";
    public static final String AGENT_NAME = "agentName";

    private final ChatClient chatClient;
    private final UserPreferencesService userPreferencesService;
    private final SlashCommandService slashCommandService;
    private final VectorStoreService vectorStoreService;
    private final McpSyncClient mcpClient;
    private final McpPromptAdapter mcpPromptAdapter;
    private final ToolCallService toolCallService;
    private final Optional<A2AAgentService> a2aAgentService;
    private final Optional<A2AStickyAgentService> a2aStickyAgentService;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    public PromptService(
            @Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            SlashCommandService slashCommandService,
            VectorStoreService vectorStoreService,
            McpSyncClient mcpClient,
            McpPromptAdapter mcpPromptAdapter,
            ToolCallService toolCallService,
            Optional<A2AAgentService> a2aAgentService,
            Optional<A2AStickyAgentService> a2aStickyAgentService) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.slashCommandService = slashCommandService;
        this.vectorStoreService = vectorStoreService;
        this.mcpClient = mcpClient;
        this.mcpPromptAdapter = mcpPromptAdapter;
        this.toolCallService = toolCallService;
        this.a2aAgentService = a2aAgentService;
        this.a2aStickyAgentService = a2aStickyAgentService;
    }

    public String model(UUID userId) {
        return userPreferencesService.get(userId).getModel();
    }

    public Flux<String> stream(UUID chatId, UUID userId, ChatRequest chatMessage, Authentication authentication) {
        log.info("Streaming prompt for chat id {}", chatId);
        String model = model(userId);
        String message = chatMessage.chatMessage();
        Set<String> commands = chatMessage.commands();

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof Jwt jwt)) {
            throw new IllegalStateException("Authentication principal is not a JWT token");
        }

        String authToken = jwt.getTokenValue();

        Map<String, Object> contextMap = Map.of(
                USER_TOKEN, authToken,
                CHAT_ID, chatId,
                PROGRESS_TOKEN, chatId);

        Advisor retrievalAugmentationAdvisor = vectorStoreService.retrievalAugmentationAdvisor(userId);

        if (CollectionUtils.isEmpty(commands)) {
            if (a2aStickyAgentService.isPresent() && a2aAgentService.isPresent()) {
                return a2aStickyAgentService.get()
                        .getActiveAgent(chatId)
                        .flatMapMany(stickyAgent -> {
                            if (stickyAgent.isPresent()) {
                                log.info("Routing to sticky A2A agent '{}' for chat {}", stickyAgent.get(), chatId);

                                return a2aAgentService.get().delegate(chatId, stickyAgent.get(), message, authToken);
                            }

                            log.info("No command or sticky agent, using basic-prompt from MCP.");

                            return streamBasicPrompt(chatId, message, contextMap, retrievalAugmentationAdvisor, model);
                        });
            }

            log.info("No command or sticky agent, using basic-prompt from MCP.");

            return streamBasicPrompt(chatId, message, contextMap, retrievalAugmentationAdvisor, model);
        }

        List<SlashCommand> slashCommands = slashCommandService.commands(commands);

        SlashCommand slashCommand = slashCommands.stream()
                .findFirst()
                .orElseThrow(IllegalStateException::new);

        return switch (slashCommand) {
            case PromptSlashCommand promptCommand -> {
                log.info("Prompt invoke: {}", promptCommand.name());
                a2aStickyAgentService.ifPresent(stickyService -> stickyService.deactivate(chatId).subscribe());

                McpSchema.GetPromptRequest getPromptRequest = McpSchema.GetPromptRequest.builder(promptCommand.name())
                        .arguments(Map.of(USER_MESSAGE, message, AGENT_NAME, agentName))
                        .build();

                McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);
                Prompt prompt = promptCommand.buildPrompt(getPromptResult, message);

                yield chatClient.prompt(prompt)
                        .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, chatId))
                        .advisors(retrievalAugmentationAdvisor)
                        .tools(toolSpec -> toolSpec.context(contextMap))
                        .options(OllamaChatOptions.builder().model(model))
                        .stream()
                        .content();
            }
            case ToolSlashCommand toolCommand -> {
                a2aStickyAgentService.ifPresent(stickyService -> stickyService.deactivate(chatId).subscribe());
                yield toolCallService.stream(chatId, message, toolCommand, contextMap, retrievalAugmentationAdvisor);
            }
            case AgentSlashCommand agentCommand -> {
                log.info("A2A agent invoke: {}", agentCommand.command());
                A2AAgentService agentService = a2aAgentService.orElseThrow(
                        () -> new IllegalStateException("A2A agent service is not configured"));

                a2aStickyAgentService.ifPresent(stickyService ->
                        stickyService.activate(chatId, agentCommand.command()).subscribe());

                yield agentService.delegate(chatId, agentCommand.command(), message, authToken);
            }
        };
    }

    private Flux<String> streamBasicPrompt(UUID chatId, String message, Map<String, Object> contextMap,
                                           Advisor retrievalAugmentationAdvisor, String model) {

        McpSchema.GetPromptRequest getPromptRequest = McpSchema.GetPromptRequest.builder(BASIC_PROMPT)
                .arguments(Map.of(USER_MESSAGE, message, AGENT_NAME, agentName))
                .build();

        McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);
        String systemText = mcpPromptAdapter.toSystemText(getPromptResult);

        return chatClient.prompt()
                .system(systemText)
                .user(message)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .tools(toolSpec -> toolSpec.context(contextMap))
                .options(OllamaChatOptions.builder().model(model))
                .stream()
                .content();
    }
}
