package com.solesonic.service.prompt;

import com.solesonic.mcp.client.prompt.McpPromptAdapter;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.service.vision.ImageDescriptionService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
    private final McpSyncClient mcpClient;
    private final McpPromptAdapter mcpPromptAdapter;
    private final ToolCallService toolCallService;
    private final A2AAgentService a2aAgentService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final VectorStoreService vectorStoreService;
    private final ImageDescriptionService imageDescriptionService;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    public PromptService(
            @Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
            UserPreferencesService userPreferencesService,
            SlashCommandService slashCommandService,
            McpSyncClient mcpClient,
            McpPromptAdapter mcpPromptAdapter,
            ToolCallService toolCallService,
            A2AAgentService a2aAgentService,
            A2AStickyAgentService a2aStickyAgentService,
            VectorStoreService vectorStoreService,
            ImageDescriptionService imageDescriptionService) {
        this.chatClient = chatClient;
        this.userPreferencesService = userPreferencesService;
        this.slashCommandService = slashCommandService;
        this.mcpClient = mcpClient;
        this.mcpPromptAdapter = mcpPromptAdapter;
        this.toolCallService = toolCallService;
        this.a2aAgentService = a2aAgentService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.vectorStoreService = vectorStoreService;
        this.imageDescriptionService = imageDescriptionService;
    }

    public String model(UUID userId) {
        return userPreferencesService.get(userId).getModel();
    }

    public Flux<String> stream(UUID chatId, UUID userId, ChatRequest chatMessage, Authentication authentication) {
        log.info("Streaming prompt for chat id {}", chatId);
        String model = model(userId);
        String message = chatMessage.chatMessage();
        Set<String> commands = chatMessage.commands();

        // Image attachments reach the model as text: descriptions produced by a separate vision
        // model, prepended to the user's message. Tool routes keep the original message on purpose
        // — see the ToolSlashCommand branch below.
        String augmentedMessage = imageDescriptionService
                .augment(chatId, userId, message, chatMessage.attachmentIds());

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof Jwt jwt)) {
            throw new IllegalStateException("Authentication principal is not a JWT token");
        }

        String authToken = jwt.getTokenValue();

        Map<String, Object> contextMap = Map.of(
                USER_TOKEN, authToken,
                CHAT_ID, chatId,
                PROGRESS_TOKEN, chatId);

        if (CollectionUtils.isEmpty(commands)) {
            return a2aStickyAgentService
                    .getActiveAgent(chatId)
                    .flatMapMany(stickyAgent -> {
                        if (stickyAgent.isPresent()) {
                            log.info("Routing to sticky A2A agent '{}' for chat {}", stickyAgent.get(), chatId);

                            //The remote agent is text-only, so a description is the only way it
                            //learns that an image was attached at all.
                            return a2aAgentService.delegate(chatId, stickyAgent.get(), augmentedMessage, authToken);
                        }

                        log.info("No command or sticky agent, using basic-prompt from MCP.");

                        return streamBasicPrompt(chatId, userId, message, augmentedMessage, contextMap, model);
                    });
        }

        List<SlashCommand> slashCommands = slashCommandService.commands(commands);

        SlashCommand slashCommand = slashCommands.stream()
                .findFirst()
                .orElseThrow(IllegalStateException::new);

        return switch (slashCommand) {
            case PromptSlashCommand promptCommand -> {
                log.info("Prompt invoke: {}", promptCommand.name());

                McpSchema.GetPromptRequest getPromptRequest = McpSchema.GetPromptRequest.builder(promptCommand.name())
                        .arguments(Map.of(USER_MESSAGE, message, AGENT_NAME, agentName))
                        .build();

                McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);

                //The MCP call above renders a prompt template and wants the user's actual words;
                //the prompt sent to the model carries the image descriptions too.
                Prompt prompt = promptCommand.buildPrompt(getPromptResult, augmentedMessage);

                yield a2aStickyAgentService.deactivate(chatId)
                        .thenMany(chatClient.prompt(prompt)
                                .advisors(vectorStoreService.retrievalAugmentationAdvisor(userId))
                                .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, chatId))
                                .toolContext(contextMap)
                                .options(OllamaChatOptions.builder().model(model))
                                .stream()
                                .content());
            }
            //Tool routes get the original message, not the augmented one: the task prompt tells the
            //model to invoke the tool with the exact user message as input, so prepending image
            //descriptions would put image prose into the tool's arguments.
            case ToolSlashCommand toolCommand -> a2aStickyAgentService.deactivate(chatId)
                    .thenMany(toolCallService.stream(chatId, message, toolCommand, contextMap));
            case LocalToolSlashCommand localToolCommand -> a2aStickyAgentService.deactivate(chatId)
                    .thenMany(toolCallService.streamLocal(chatId, message, localToolCommand, contextMap));
            case AgentSlashCommand agentCommand -> {
                log.info("A2A agent invoke: {}", agentCommand.command());

                yield a2aStickyAgentService.activate(chatId, agentCommand.command())
                        .thenMany(a2aAgentService.delegate(chatId, agentCommand.command(), augmentedMessage, authToken));
            }
        };
    }

    /**
     * @param message          the user's own words, used to render the MCP prompt template
     * @param augmentedMessage the same message plus any image descriptions, sent to the model
     */
    private Flux<String> streamBasicPrompt(UUID chatId,
                                           UUID userId,
                                           String message,
                                           String augmentedMessage,
                                           Map<String, Object> contextMap,
                                           String model) {
        String systemText = loadBasicPromptSystemText(message);

        var promptSpec = chatClient.prompt()
                .user(augmentedMessage)
                .advisors(vectorStoreService.retrievalAugmentationAdvisor(userId))
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .toolContext(contextMap)
                .options(OllamaChatOptions.builder().model(model));

        if (systemText != null) {
            promptSpec = promptSpec.system(systemText);
        }

        return promptSpec.stream().content();
    }

    private String loadBasicPromptSystemText(String message) {
        try {
            McpSchema.GetPromptRequest getPromptRequest = McpSchema.GetPromptRequest.builder(BASIC_PROMPT)
                    .arguments(Map.of(USER_MESSAGE, message, AGENT_NAME, agentName))
                    .build();
            McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);
            return mcpPromptAdapter.toSystemText(getPromptResult);
        } catch (Exception exception) {
            log.warn("Could not load '{}' prompt from MCP server, proceeding without system prompt: {}", BASIC_PROMPT, exception.getMessage());
            return null;
        }
    }
}
