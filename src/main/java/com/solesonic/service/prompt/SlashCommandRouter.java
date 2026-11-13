package com.solesonic.service.prompt;

import com.solesonic.mcp.client.McpIdentityProvider;
import com.solesonic.model.chat.ResponseMetadataCapture;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.ModelSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ConversationSessionService;
import com.solesonic.service.chat.ConversationTurn;
import com.solesonic.service.litellm.LiteLlmHeaderRegistry;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.util.AttachmentContextFormatter;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.config.chat.ChatConfig.DEFAULT_CHAT_CLIENT;
import static com.solesonic.config.chat.ResponseMetadataCaptureAdvisor.RESPONSE_METADATA_CAPTURE;
import static com.solesonic.config.chat.SessionEventCorrelationAdvisor.SESSION_TURN_RECORD;
import static com.solesonic.service.prompt.ChatStreamSupport.capturingContentFlux;
import static com.solesonic.service.prompt.ChatStreamSupport.chatOptions;
import static com.solesonic.service.prompt.PromptService.AGENT_NAME;
import static com.solesonic.service.prompt.PromptService.USER_MESSAGE;

/**
 * Dispatches a resolved slash command to the one of four routes that handles it.
 * <p>
 * The sticky-agent bookkeeping is the reason this is a single decision rather than unrelated ones:
 * every routed command either activates a sticky A2A agent or clears one, so that a conversation
 * previously pinned to a remote agent is released the moment the user asks for anything else.
 * {@code /model} is a setting command applied through REST, so it never arrives here.
 */
@Service
public class SlashCommandRouter {
    private static final Logger log = LoggerFactory.getLogger(SlashCommandRouter.class);

    private final ChatClient chatClient;
    private final McpSyncClient mcpClient;
    private final McpIdentityProvider mcpIdentityProvider;
    private final ToolCallService toolCallService;
    private final A2AAgentService a2aAgentService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final VectorStoreService vectorStoreService;
    private final ChatMessageService chatMessageService;
    private final LiteLlmHeaderRegistry liteLlmHeaderRegistry;
    private final ConversationSessionService conversationSessionService;
    private final String agentName;
    private final Duration chatTimeout;

    public SlashCommandRouter(@Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
                              McpSyncClient mcpClient,
                              McpIdentityProvider mcpIdentityProvider,
                              ToolCallService toolCallService,
                              A2AAgentService a2aAgentService,
                              A2AStickyAgentService a2aStickyAgentService,
                              VectorStoreService vectorStoreService,
                              ChatMessageService chatMessageService,
                              LiteLlmHeaderRegistry liteLlmHeaderRegistry,
                              ConversationSessionService conversationSessionService,
                              @Value("${solesonic.llm.bot.name}") String agentName,
                              @Value("${spring.ai.openai.chat.timeout}") Duration chatTimeout) {
        this.conversationSessionService = conversationSessionService;
        this.chatClient = chatClient;
        this.mcpClient = mcpClient;
        this.mcpIdentityProvider = mcpIdentityProvider;
        this.toolCallService = toolCallService;
        this.a2aAgentService = a2aAgentService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.vectorStoreService = vectorStoreService;
        this.chatMessageService = chatMessageService;
        this.liteLlmHeaderRegistry = liteLlmHeaderRegistry;
        this.agentName = agentName;
        this.chatTimeout = chatTimeout;
    }

    /**
     * @param message     the user's own words. The tool routes are given nothing else — image prose
     *                    in a tool's arguments is a search for the wrong thing
     * @param inFlightMessage the turn's saved user row, which the model routes hand to the session
     * @param attachments what this send's attachments amount to: a rendered block for the routes
     *                    with a message structure, the descriptions themselves for the A2A route,
     *                    which takes a single string
     * @param contextMap  tool context forwarded to any MCP tool called mid-turn
     * @param model       the chat model this turn uses — the chat's selection or the default,
     *                    resolved once by {@code PromptService}
     * @param llmApiKey   the user's own key, which every model call of the turn carries
     */
    public Flux<String> route(SlashCommand slashCommand,
                              UUID chatId,
                              UUID userId,
                              String message,
                              ChatMessage inFlightMessage,
                              AttachmentResolution attachments,
                              Map<String, Object> contextMap,
                              String model,
                              LlmApiKey llmApiKey) {

        return route(slashCommand, chatId, userId, message, inFlightMessage, attachments, contextMap, model, llmApiKey, null);
    }

    /**
     * {@link #route} for a command sent in a shared conversation, whose answer the whole room reads.
     * Whatever the command does, it does with the conversation's own scope:
     * <ul>
     *   <li>a tool command runs only if its tool is in {@code allowedTools};</li>
     *   <li>an MCP prompt retrieves from the conversation and the global corpus (not the sender's library)
     *       and may call only {@code allowedTools};</li>
     *   <li>an agent command is never run, because it would run as the sender and pin the chat.</li>
     * </ul>
     * Reaching a refusal here means a command got past {@code SharedCommandPolicy}, which is a bug.
     *
     * @param allowedTools the tools a shared conversation may call
     */
    public Flux<String> routeShared(SlashCommand slashCommand,
                                    UUID chatId,
                                    UUID userId,
                                    String message,
                                    ChatMessage inFlightMessage,
                                    AttachmentResolution attachments,
                                    Map<String, Object> contextMap,
                                    String model,
                                    LlmApiKey llmApiKey,
                                    Set<String> allowedTools) {

        return route(slashCommand, chatId, userId, message, inFlightMessage, attachments, contextMap, model, llmApiKey,
                Set.copyOf(allowedTools));
    }

    /**
     * @param sharedTools null for a private chat; for a shared conversation, the tools it may call
     */
    private Flux<String> route(SlashCommand slashCommand,
                               UUID chatId,
                               UUID userId,
                               String message,
                               ChatMessage inFlightMessage,
                               AttachmentResolution attachments,
                               Map<String, Object> contextMap,
                               String model,
                               LlmApiKey llmApiKey,
                               Set<String> sharedTools) {

        return switch (slashCommand) {
            case PromptSlashCommand promptCommand -> releasingStickyAgent(chatId,
                    streamMcpPrompt(promptCommand, chatId, userId, message, inFlightMessage, attachments, contextMap, model,
                            llmApiKey, sharedTools));

            case ToolSlashCommand toolCommand -> {
                requireSharedTool(sharedTools, toolCommand.name());

                yield releasingStickyAgent(chatId,
                        toolCallService.stream(chatId, message, inFlightMessage, toolCommand, contextMap, llmApiKey));
            }

            case LocalToolSlashCommand localToolCommand -> {
                requireSharedTool(sharedTools, localToolCommand.name());

                yield releasingStickyAgent(chatId,
                        toolCallService.streamLocal(chatId, message, inFlightMessage, localToolCommand, contextMap, llmApiKey));
            }

            case AgentSlashCommand agentCommand -> {
                if (sharedTools != null) {
                    throw new IllegalStateException("A shared conversation never runs an agent command: " + agentCommand.command());
                }

                log.info("A2A agent invoke: {}", agentCommand.command());

                yield a2aStickyAgentService.activate(chatId, agentCommand.command())
                        .thenMany(delegate(chatId, agentCommand.command(), message, attachments));
            }

            case ModelSlashCommand _ -> throw new IllegalStateException(
                    "/model is a setting command and is never routed as a chat turn");
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
                                         ChatMessage inFlightMessage,
                                         AttachmentResolution attachments,
                                         Map<String, Object> contextMap,
                                         String model,
                                         LlmApiKey llmApiKey,
                                         Set<String> sharedTools) {

        log.info("Prompt invoke: {}", promptCommand.name());

        //Taken before the call, so the lookup that attaches the turn's accounting finds the row this
        //turn's completion writes rather than the previous turn's.
        ZonedDateTime since = ZonedDateTime.now();

        //Joins this turn to the x-litellm-* headers of the HTTP calls it is about to make; the two
        //reach the application on different threads and share nothing else.
        UUID correlationId = UUID.randomUUID();

        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        McpSchema.GetPromptRequest getPromptRequest = McpSchema.GetPromptRequest.builder(promptCommand.name())
                .arguments(Map.of(USER_MESSAGE, message, AGENT_NAME, agentName))
                .build();

        McpSchema.GetPromptResult getPromptResult = mcpClient.getPrompt(getPromptRequest);

        Prompt prompt = promptCommand.buildPrompt(getPromptResult, message, attachments.attachmentContext());
        ToolCallback[] toolCallbacks = sharedTools == null
                ? mcpIdentityProvider.getToolCallbacks()
                : mcpIdentityProvider.getToolCallbacks(sharedTools).toArray(new ToolCallback[0]);

        Advisor retrievalAdvisor = sharedTools == null
                ? vectorStoreService.retrievalAugmentationAdvisor(userId, chatId, llmApiKey)
                : vectorStoreService.sharedRetrievalAugmentationAdvisor(chatId, llmApiKey);

        ConversationTurn conversationTurn = conversationSessionService.prepareTurn(chatId, inFlightMessage,
                leadingContext(prompt, attachments.attachmentContext()));

        Flux<ChatResponse> promptChatResponse = chatClient.prompt(prompt)
                .tools((Object[]) toolCallbacks)
                .advisors(retrievalAdvisor)
                .advisors(SessionAdvisors.session(conversationTurn.sessionId()))
                .advisors(advisorSpec -> advisorSpec
                        .param(SESSION_TURN_RECORD, conversationTurn.record())
                        .param(RESPONSE_METADATA_CAPTURE, responseMetadataCapture))
                .toolContext(contextMap)
                .options(chatOptions(model, chatTimeout, correlationId, llmApiKey))
                .stream()
                .chatResponse();

        return capturingContentFlux(promptChatResponse, responseMetadataCapture, chatMessageService,
                liteLlmHeaderRegistry, since, correlationId, conversationTurn);
    }

    /**
     * The attachment context the session must end with for the request to overlap it by exactly one
     * message: only when the MCP prompt opens with it. A prompt that opens otherwise is sent as it is,
     * and the context reaches the model from the request alone.
     */
    private static String leadingContext(Prompt prompt, String attachmentContext) {
        if (attachmentContext == null) {
            return null;
        }

        return prompt.getInstructions().stream()
                .filter(instruction -> !(instruction instanceof SystemMessage))
                .findFirst()
                .filter(instruction -> instruction instanceof UserMessage && attachmentContext.equals(instruction.getText()))
                .map(_ -> attachmentContext)
                .orElse(null);
    }

    private static void requireSharedTool(Set<String> sharedTools, String tool) {
        if (sharedTools != null && !sharedTools.contains(tool)) {
            throw new IllegalStateException("A shared conversation does not allow the tool " + tool);
        }
    }

    /**
     * A remote agent takes a single string, so the described-images block is inlined ahead of the
     * user's words rather than carried as a message of its own.
     */
    private Flux<String> delegate(UUID chatId,
                                  String agentCommand,
                                  String message,
                                  AttachmentResolution attachments) {

        return a2aAgentService.delegate(chatId, agentCommand,
                AttachmentContextFormatter.prepend(message, attachments.imageDescriptions()));
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
