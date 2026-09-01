package com.solesonic.service.prompt;

import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.util.AttachmentContextFormatter;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

import static com.solesonic.config.chat.ChatConfig.DEFAULT_CHAT_CLIENT;
import static com.solesonic.service.prompt.ChatStreamSupport.chatOptions;
import static com.solesonic.service.prompt.ChatStreamSupport.contentFlux;
import static com.solesonic.service.prompt.PromptService.AGENT_NAME;
import static com.solesonic.service.prompt.PromptService.USER_MESSAGE;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * Dispatches a resolved slash command to the one of four routes that handles it.
 * <p>
 * The sticky-agent bookkeeping is the reason this is a single decision rather than four unrelated
 * ones: every command either activates a sticky A2A agent or clears one, so that a conversation
 * previously pinned to a remote agent is released the moment the user asks for anything else.
 */
@Service
public class SlashCommandRouter {
    private static final Logger log = LoggerFactory.getLogger(SlashCommandRouter.class);

    private final ChatClient chatClient;
    private final McpSyncClient mcpClient;
    private final ToolCallService toolCallService;
    private final A2AAgentService a2aAgentService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final VectorStoreService vectorStoreService;
    private final String agentName;
    private final String defaultChatModel;

    public SlashCommandRouter(@Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
                              McpSyncClient mcpClient,
                              ToolCallService toolCallService,
                              A2AAgentService a2aAgentService,
                              A2AStickyAgentService a2aStickyAgentService,
                              VectorStoreService vectorStoreService,
                              @Value("${solesonic.llm.bot.name}") String agentName,
                              @Value("${spring.ai.openai.model}") String defaultChatModel) {
        this.chatClient = chatClient;
        this.mcpClient = mcpClient;
        this.toolCallService = toolCallService;
        this.a2aAgentService = a2aAgentService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.vectorStoreService = vectorStoreService;
        this.agentName = agentName;
        this.defaultChatModel = defaultChatModel;
    }

    /**
     * @param message     the user's own words. The tool routes are given nothing else — image prose
     *                    in a tool's arguments is a search for the wrong thing
     * @param attachments what this send's attachments amount to: a rendered block for the routes
     *                    with a message structure, the descriptions themselves for the A2A route,
     *                    which takes a single string
     * @param contextMap  tool context forwarded to any MCP tool called mid-turn
     * @param authToken   the user's own bearer token, so a remote agent acts as the user
     */
    public Flux<String> route(SlashCommand slashCommand,
                              UUID chatId,
                              UUID userId,
                              String message,
                              AttachmentResolution attachments,
                              Map<String, Object> contextMap,
                              String authToken) {

        return switch (slashCommand) {
            case PromptSlashCommand promptCommand -> releasingStickyAgent(chatId,
                    streamMcpPrompt(promptCommand, chatId, userId, message, attachments, contextMap));

            case ToolSlashCommand toolCommand -> releasingStickyAgent(chatId,
                    toolCallService.stream(chatId, message, toolCommand, contextMap));

            case LocalToolSlashCommand localToolCommand -> releasingStickyAgent(chatId,
                    toolCallService.streamLocal(chatId, message, localToolCommand, contextMap));

            case AgentSlashCommand agentCommand -> {
                log.info("A2A agent invoke: {}", agentCommand.command());

                yield a2aStickyAgentService.activate(chatId, agentCommand.command())
                        .thenMany(delegate(chatId, agentCommand.command(), message, attachments, authToken));
            }
        };
    }

    /**
     * Fetches the named prompt from the MCP server and streams a one-off call built from it.
     * <p>
     * The MCP round trip happens here, eagerly, rather than inside the returned {@code Flux} —
     * which is what {@code buildPrompt} needs, since the prompt has to exist before the call can be
     * described at all.
     */
    private Flux<String> streamMcpPrompt(PromptSlashCommand promptCommand,
                                         UUID chatId,
                                         UUID userId,
                                         String message,
                                         AttachmentResolution attachments,
                                         Map<String, Object> contextMap) {

        log.info("Prompt invoke: {}", promptCommand.name());

        McpSchema.GetPromptRequest getPromptRequest = McpSchema.GetPromptRequest.builder(promptCommand.name())
                .arguments(Map.of(USER_MESSAGE, message, AGENT_NAME, agentName))
                .build();

        McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);

        Prompt prompt = promptCommand.buildPrompt(getPromptResult, message, attachments.attachmentContext());

        Flux<ChatResponse> promptChatResponse = chatClient.prompt(prompt)
                .advisors(vectorStoreService.retrievalAugmentationAdvisor(userId, chatId))
                .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, chatId))
                .toolContext(contextMap)
                .options(chatOptions(defaultChatModel))
                .stream()
                .chatResponse();

        return contentFlux(promptChatResponse);
    }

    /**
     * A remote agent takes a single string, so the described-images block is inlined ahead of the
     * user's words rather than carried as a message of its own.
     */
    private Flux<String> delegate(UUID chatId,
                                  String agentCommand,
                                  String message,
                                  AttachmentResolution attachments,
                                  String authToken) {

        return a2aAgentService.delegate(chatId, agentCommand,
                AttachmentContextFormatter.prepend(message, attachments.imageDescriptions()), authToken);
    }

    /**
     * Clears any sticky A2A agent before the route's own output flows.
     * <p>
     * {@code stream} is evaluated by the caller, before the deactivation is subscribed to — which is
     * what the MCP-prompt route depends on, and what the tool routes have always done.
     */
    private Flux<String> releasingStickyAgent(UUID chatId, Flux<String> stream) {
        return a2aStickyAgentService.deactivate(chatId).thenMany(stream);
    }
}
