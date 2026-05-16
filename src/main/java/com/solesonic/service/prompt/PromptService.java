
package com.solesonic.service.prompt;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.prompt.SlashCommand;
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
import static com.solesonic.model.prompt.SlashCommand.*;
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
            Optional<A2AAgentService> a2aAgentService,
            Optional<A2AStickyAgentService> a2aStickyAgentService) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.slashCommandService = slashCommandService;
        this.vectorStoreService = vectorStoreService;
        this.mcpClient = mcpClient;
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
                Optional<String> stickyAgent = a2aStickyAgentService.get().getActiveAgent(chatId).block();

                if (stickyAgent != null && stickyAgent.isPresent()) {
                    log.info("Routing to sticky A2A agent '{}' for chat {}", stickyAgent.get(), chatId);

                    return a2aAgentService.get().delegate(chatId, stickyAgent.get(), message, authToken);
                }
            }

            log.info("No command or sticky agent, using basic-prompt from MCP.");

            return streamBasicPrompt(chatId, message, contextMap, retrievalAugmentationAdvisor, model);
        }

        List<SlashCommand> slashCommands = slashCommandService.commands(commands);

        SlashCommand slashCommand = slashCommands.stream()
                .findFirst()
                .orElseThrow(IllegalStateException::new);

        switch (slashCommand.commandType) {
            case PROMPT -> {
                log.info("Prompt invoke: {}", slashCommand.name());
                a2aStickyAgentService.ifPresent(stickyService -> stickyService.deactivate(chatId).subscribe());

                McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest(
                        slashCommand.name(),
                        Map.of(USER_MESSAGE, message, AGENT_NAME, agentName)
                );

                McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);
                Prompt prompt = slashCommand.preparePrompt(getPromptResult, message);

                return chatClient.prompt(prompt)
                        .advisors(advisorSpec -> advisorSpec
                                .param(CONVERSATION_ID, chatId)
                        )
                        .advisors(retrievalAugmentationAdvisor)
                        .toolContext(contextMap)
                        .options(OllamaChatOptions.builder().model(model))
                        .stream()
                        .content();
            }
            case AGENT -> {
                log.info("A2A agent invoke: {}", slashCommand.command);

                A2AAgentService agentService = a2aAgentService.orElseThrow(
                        () -> new IllegalStateException("A2A agent service is not configured"));

                a2aStickyAgentService.ifPresent(stickyService ->
                        stickyService.activate(chatId, slashCommand.command()).subscribe());

                return agentService.delegate(chatId, slashCommand.command(), message, authToken);
            }
            default -> throw new IllegalStateException();
        }
    }

    private Flux<String> streamBasicPrompt(UUID chatId, String message, Map<String, Object> contextMap,
                                           Advisor retrievalAugmentationAdvisor, String model) {
        McpSchema.GetPromptRequest getPromptRequest = new McpSchema.GetPromptRequest(
                BASIC_PROMPT,
                Map.of(USER_MESSAGE, message, AGENT_NAME, agentName)
        );

        McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);
        Prompt prompt = new SlashCommand().preparePrompt(getPromptResult, message);

        return chatClient.prompt(prompt)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .toolContext(contextMap)
                .options(OllamaChatOptions.builder().model(model))
                .stream()
                .content();
    }
}