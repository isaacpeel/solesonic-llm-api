package com.solesonic.service.prompt;

import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.mcp.client.McpIdentityProvider;
import com.solesonic.model.address.Address;
import com.solesonic.model.chat.ChatRequest;
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
import com.solesonic.service.address.AddressService;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ConversationSessionService;
import com.solesonic.service.chat.ConversationTurn;
import com.solesonic.service.etl.ChatDocumentHealer;
import com.solesonic.service.chat.ChatModelSelection;
import com.solesonic.service.litellm.LiteLlmHeaderRegistry;
import com.solesonic.service.llm.LlmCredentialScope;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.memory.UserMemoryService;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.prompt.AttachmentContextResolver.ImageHandling;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.tools.LocalToolRegistry;
import com.solesonic.util.AttachmentContextFormatter;
import com.solesonic.util.AuthenticationTokens;
import com.solesonic.util.SharedMessageText;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.solesonic.config.chat.ChatConfig.DEFAULT_CHAT_CLIENT;
import static com.solesonic.config.chat.ResponseMetadataCaptureAdvisor.RESPONSE_METADATA_CAPTURE;
import static com.solesonic.config.chat.SessionEventCorrelationAdvisor.SESSION_TURN_RECORD;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;
import static com.solesonic.service.prompt.ChatStreamSupport.capturingContentFlux;
import static com.solesonic.service.prompt.ChatStreamSupport.chatOptions;
import static com.solesonic.tools.ImageGenerationTool.GENERATE_IMAGE;

/**
 * Where every incoming chat message is routed.
 * <p>
 * Only two decisions are made here — whether the send named a slash command, and, when it did not,
 * whether the conversation is pinned to a remote agent. Everything each branch then does belongs to
 * a collaborator: {@link AttachmentContextResolver} for what the attachments amount to,
 * {@link SlashCommandRouter} for the five command routes. What is left in this class is the
 * no-command default LLM path, which is its actual reason for existing.
 */
@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    public static final String CHAT_ID = "chatId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String PROGRESS_TOKEN = "progressToken";
    public static final String AGENT_NAME = "agentName";
    public static final String USER_ADDRESS = "userAddress";
    public static final String CURRENT_DATE_TIME = "currentDateTime";
    public static final String USER_MEMORY = "userMemory";

    /**
     * Tool context key for the image attachment ids named by the current send, which the image tool
     * sends as reference images. Stripped before an MCP call leaves: the tool context becomes the
     * request's {@code _meta}.
     */
    public static final String REFERENCE_ATTACHMENT_IDS = "referenceAttachmentIds";
    /**
     * Left out of a shared conversation's prompt, where only some commands run.
     */
    private static final String SLASH_COMMAND_HINT = "If a request needs a capability you don't have in plain chat, tell the user they "
            + "can type \"/\" to browse available slash commands, tools, and agents.";
    private static final DateTimeFormatter CURRENT_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a (zzz)", Locale.US);

    /**
     * The MCP tools the no-slash-command default chat path may call.
     */
    private static final Set<String> DEFAULT_PROMPT_TOOLS = Set.of(
            "web_search",
            "web_search_advanced",
            "web_search_news",
            "web_extract_content"
    );

    private final ChatClient chatClient;
    private final SlashCommandService slashCommandService;
    private final SlashCommandRouter slashCommandRouter;
    private final AttachmentContextResolver attachmentContextResolver;
    private final A2AAgentService a2aAgentService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final ChatModelSelection chatModelSelection;
    private final VectorStoreService vectorStoreService;
    private final UserPreferencesService userPreferencesService;
    private final McpIdentityProvider mcpIdentityProvider;
    private final ChatMessageService chatMessageService;
    private final UserMemoryService userMemoryService;
    private final LiteLlmHeaderRegistry liteLlmHeaderRegistry;
    private final UserLlmKeyService userLlmKeyService;
    private final LocalToolRegistry localToolRegistry;
    private final ChatDocumentHealer chatDocumentHealer;
    private final SharedSessionSettings sharedSessionSettings;
    private final ConversationSessionService conversationSessionService;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    @Value("classpath:prompts/basic-system-prompt.st")
    Resource defaultSystemPromptResource;

    @Value("classpath:prompts/shared-conversation-system.st")
    Resource sharedSystemPromptResource;

    private final String defaultChatModel;
    private final Duration chatTimeout;

    public PromptService(
            @Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
            SlashCommandService slashCommandService,
            SlashCommandRouter slashCommandRouter,
            AttachmentContextResolver attachmentContextResolver,
            A2AAgentService a2aAgentService,
            A2AStickyAgentService a2aStickyAgentService,
            ChatModelSelection chatModelSelection,
            VectorStoreService vectorStoreService,
            UserPreferencesService userPreferencesService,
            McpIdentityProvider mcpIdentityProvider,
            ChatMessageService chatMessageService,
            UserMemoryService userMemoryService,
            LiteLlmHeaderRegistry liteLlmHeaderRegistry,
            UserLlmKeyService userLlmKeyService,
            LocalToolRegistry localToolRegistry,
            ChatDocumentHealer chatDocumentHealer,
            SharedSessionSettings sharedSessionSettings,
            ConversationSessionService conversationSessionService,
            @Value("${spring.ai.openai.model}") String defaultChatModel,
            @Value("${spring.ai.openai.chat.timeout}") Duration chatTimeout) {
        this.conversationSessionService = conversationSessionService;
        this.chatClient = chatClient;
        this.slashCommandService = slashCommandService;
        this.slashCommandRouter = slashCommandRouter;
        this.attachmentContextResolver = attachmentContextResolver;
        this.a2aAgentService = a2aAgentService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.chatModelSelection = chatModelSelection;
        this.vectorStoreService = vectorStoreService;
        this.userPreferencesService = userPreferencesService;
        this.mcpIdentityProvider = mcpIdentityProvider;
        this.chatMessageService = chatMessageService;
        this.userMemoryService = userMemoryService;
        this.liteLlmHeaderRegistry = liteLlmHeaderRegistry;
        this.userLlmKeyService = userLlmKeyService;
        this.localToolRegistry = localToolRegistry;
        this.chatDocumentHealer = chatDocumentHealer;
        this.sharedSessionSettings = sharedSessionSettings;
        this.defaultChatModel = defaultChatModel;
        this.chatTimeout = chatTimeout;
    }

    /**
     * One message of a shared conversation, answered as {@code senderUserId}: their model key and
     * their token, with nothing of theirs that the other participants must not see. The shared session
     * calls this for every message.
     * <p>
     * It is the same turn as {@link #stream} with the private parts taken out ({@link SharedPromptOptions}):
     * no memory, no address or time zone, no sticky agent, retrieval from the conversation's own documents
     * and the global corpus (never the sender's library), and only the tools the session's allowlist
     * names. The message may carry the sender's attachments, which are described and indexed exactly as in
     * a private chat, and any slash command {@code SharedCommandPolicy} let through, which the router runs
     * with that same tool allowlist.
     *
     * @param senderName       the sender's name in the session, which prefixes their message for the model
     * @param participantNames the names of everyone currently seated, for the system prompt
     */
    public Flux<String> streamShared(UUID chatId,
                                     UUID senderUserId,
                                     String senderName,
                                     List<String> participantNames,
                                     ChatRequest chatRequest,
                                     ChatMessage inFlightMessage,
                                     Authentication authentication) {
        log.info("Streaming shared prompt for chat id {}", chatId);

        return turn(chatId, senderUserId, chatRequest, inFlightMessage, authentication,
                new SharedPromptOptions(participantNames == null ? List.of() : List.copyOf(participantNames), senderName));
    }

    /**
     * @param inFlightMessage the turn's user row, saved before the turn started. The routes that call
     *                        the model hand it to the session, which never copies it: the advisor
     *                        stores the live message itself
     */
    public Flux<String> stream(UUID chatId,
                               UUID userId,
                               ChatRequest chatMessage,
                               ChatMessage inFlightMessage,
                               Authentication authentication) {
        log.info("Streaming prompt for chat id {}", chatId);

        return turn(chatId, userId, chatMessage, inFlightMessage, authentication, null);
    }

    /**
     * @param shared null for a private chat; for a shared conversation, what is left out of the turn and
     *               who is in the room
     */
    private Flux<String> turn(UUID chatId,
                              UUID userId,
                              ChatRequest chatMessage,
                              ChatMessage inFlightMessage,
                              Authentication authentication,
                              SharedPromptOptions shared) {
        String authToken = AuthenticationTokens.token(authentication);

        //Ahead of the attachment work for the same reason as the token: a user with no key must not
        //wait through vision and embedding calls only to be refused behind them.
        LlmApiKey llmApiKey = userLlmKeyService.requireKey(userId);

        String model = chatModel(chatId);

        Set<String> commands = chatMessage.commands();

        //Ahead of the attachment work: the route decides whether images are described at all.
        SlashCommand slashCommand = CollectionUtils.isEmpty(commands) ? null : slashCommand(commands);

        if (shared != null && slashCommand instanceof AgentSlashCommand) {
            //Refused when the message was sent; this is the backstop for a plan that got past it.
            throw new IllegalStateException("A shared conversation never runs an agent command: " + slashCommand.command());
        }

        //A shared conversation has no sticky agent: agents are refused there.
        Optional<String> stickyAgent = slashCommand == null && shared == null ? stickyAgent(chatId) : Optional.empty();
        ImageHandling imageHandling = imageHandling(slashCommand, stickyAgent.isPresent());

        //The image decision, vision and document indexing are blocking calls made on this thread, which
        //is what lets the scope carry the key to them. A shared message with nothing attached has none.
        AttachmentResolution attachments = shared != null && CollectionUtils.isEmpty(chatMessage.attachmentIds())
                ? new AttachmentResolution(List.of(), null)
                : LlmCredentialScope.asUser(llmApiKey,
                        () -> attachmentContextResolver.resolve(chatId, userId, chatMessage.chatMessage(),
                                chatMessage.attachmentIds(), imageHandling));

        log.info("Chat {} routed to {} with image handling {}: {} image(s), {} described, attachment context {} characters",
                chatId, route(slashCommand, stickyAgent.isPresent()), imageHandling, attachments.imageIds().size(),
                attachments.imageDescriptions().size(),
                attachments.attachmentContext() == null ? 0 : attachments.attachmentContext().length());

        //After the send path has indexed this turn's documents, so the background run never contends
        //with it for their lease. Only the routes that retrieve: the others would not use the result.
        //In a shared chat it heals the sender's own uploads, under their key.
        if (retrieves(slashCommand, stickyAgent.isPresent())) {
            chatDocumentHealer.schedule(chatId, userId, chatMessage.attachmentIds());
        }

        Map<String, Object> contextMap = Map.of(
                USER_TOKEN, authToken,
                USER_ID, userId,
                CHAT_ID, chatId,
                PROGRESS_TOKEN, chatId,
                REFERENCE_ATTACHMENT_IDS, attachments.imageIds());

        if (slashCommand != null) {
            return shared == null
                    ? slashCommandRouter.route(slashCommand, chatId, userId, chatMessage.chatMessage(), inFlightMessage, attachments,
                            contextMap, model, llmApiKey)
                    : slashCommandRouter.routeShared(slashCommand, chatId, userId, SharedMessageText.orNoText(chatMessage.chatMessage()),
                            inFlightMessage, attachments, contextMap, model, llmApiKey, sharedSessionSettings.conversation().tools());
        }

        if (stickyAgent.isPresent()) {
            log.info("Routing to sticky A2A agent '{}' for chat {}", stickyAgent.get(), chatId);

            //A remote agent takes a single string, so the block is inlined here rather than carried as a
            //message of its own.
            return a2aAgentService.delegate(chatId, stickyAgent.get(),
                    AttachmentContextFormatter.prepend(chatMessage.chatMessage(), attachments.imageDescriptions()));
        }

        log.info("No command or sticky agent, using default system prompt.");

        String message = shared == null
                ? chatMessage.chatMessage()
                : SharedMessageText.forModel(shared.senderName(), chatMessage.chatMessage());

        //Tools act as the sender in a shared chat, so generated images and tool progress belong to the
        //sender and the shared chat.
        return streamDefaultSystemPrompt(chatId, userId, message, inFlightMessage,
                attachments.attachmentContext(), contextMap, model, llmApiKey, shared);
    }

    private SlashCommand slashCommand(Set<String> commands) {
        return slashCommandService.commands(commands).stream()
                .findFirst()
                .orElseThrow(IllegalStateException::new);
    }

    /**
     * The routes that build the retrieval advisor: the default prompt and MCP prompt commands. Tool,
     * agent and sticky-agent routes do not retrieve (see {@code VectorStoreService}).
     */
    private static boolean retrieves(SlashCommand slashCommand, boolean stickyAgentActive) {
        if (slashCommand == null) {
            return !stickyAgentActive;
        }

        return slashCommand instanceof PromptSlashCommand;
    }

    private static String route(SlashCommand slashCommand, boolean stickyAgentActive) {
        if (slashCommand != null) {
            return slashCommand.getClass().getSimpleName() + " " + slashCommand.name();
        }

        return stickyAgentActive ? "sticky agent" : "default prompt";
    }

    /**
     * Read before the attachment work, because a pinned conversation goes to a remote agent, which
     * takes a single string and so needs every image described.
     */
    private Optional<String> stickyAgent(UUID chatId) {
        return a2aStickyAgentService.getActiveAgent(chatId)
                .blockOptional()
                .flatMap(activeAgent -> activeAgent);
    }

    /**
     * Tool routes send the images themselves and are given no image prose. On plain chat the decision
     * model reads the user's words and decides: described when answering needs what the images show,
     * references only when they are inputs for creating or editing an image. Remote agents and MCP
     * prompts always get every image described.
     */
    private static ImageHandling imageHandling(SlashCommand slashCommand, boolean stickyAgentActive) {
        if (slashCommand == null) {
            return stickyAgentActive ? ImageHandling.DESCRIBE : ImageHandling.DECIDE;
        }

        return switch (slashCommand) {
            case ToolSlashCommand _, LocalToolSlashCommand _ -> ImageHandling.REFERENCE_ONLY;
            case PromptSlashCommand _, AgentSlashCommand _, ModelSlashCommand _ -> ImageHandling.DESCRIBE;
        };
    }

    /**
     * The model this turn streams with: the one the chat was switched to with {@code /model}, or the
     * configured default. Read once, here, so every route of the turn agrees on it. A selection that
     * cannot be read costs the turn its choice of model, never its answer.
     */
    private String chatModel(UUID chatId) {
        return chatModelSelection.get(chatId)
                .onErrorResume(error -> {
                    log.warn("Could not read the model selected for chat {}, using the default: {}",
                            chatId, error.getMessage());

                    return Mono.empty();
                })
                .blockOptional()
                .orElse(defaultChatModel);
    }

    /**
     * Streams the no-slash-command, no-sticky-agent chat path: the default system prompt — built
     * once in the constructor from {@code basic-system-prompt.st} and the configured agent name,
     * not rebuilt on every call — plus the RAG retrieval advisor and the user's message.
     *
     * @param chatId       identifies the conversation to the retrieval advisor, and names its session
     * @param userId       resolves the retrieval advisor's per-user similarity threshold
     * @param message      the user's own words
     * @param inFlightMessage the turn's saved user row
     * @param attachmentContext the attachment block — described images, named documents, or both —
     *                     or null when nothing was attached. Carried as a message of its own, which
     *                     {@code DefaultChatClientUtils} places between the system text and the user
     *                     message, and which the retrieval advisor leaves alone because it only ever
     *                     rewrites the last user message
     * @param contextMap   tool context forwarded to any MCP tool the model calls mid-turn
     * @param model        the chat model name requested via {@code OpenAiChatOptions}
     * @param llmApiKey    the user's own key, which every model call of the turn carries
     * @param shared       null for a private chat; for a shared conversation, what is left out of the
     *                     prompt and who is in the room
     */
    private Flux<String> streamDefaultSystemPrompt(UUID chatId,
                                                   UUID userId,
                                                   String message,
                                                   ChatMessage inFlightMessage,
                                                   String attachmentContext,
                                                   Map<String, Object> contextMap,
                                                   String model,
                                                   LlmApiKey llmApiKey,
                                                   SharedPromptOptions shared) {

        //Taken before the call, so the lookup that attaches the turn's accounting finds the row this
        //turn's completion writes rather than the previous turn's.
        ZonedDateTime since = ZonedDateTime.now();

        ConversationTurn conversationTurn = conversationSessionService.prepareTurn(chatId, inFlightMessage, attachmentContext);

        //Joins this turn to the x-litellm-* headers of the HTTP calls it is about to make; the two
        //reach the application on different threads and share nothing else.
        UUID correlationId = UUID.randomUUID();

        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();

        Prompt defaultSystemPrompt = shared == null ? privateSystemPrompt(userId, chatId, message) : sharedSystemPrompt(shared);

        List<ToolCallback> toolCallbacks = shared == null ? privateToolCallbacks() : sharedToolCallbacks();

        Advisor retrievalAdvisor = shared == null
                ? vectorStoreService.retrievalAugmentationAdvisor(userId, chatId, llmApiKey)
                : vectorStoreService.sharedRetrievalAugmentationAdvisor(chatId, llmApiKey);

        var promptSpec = chatClient.prompt()
                .system(defaultSystemPrompt.getContents())
                .user(message)
                .tools(toolCallbacks)
                .advisors(retrievalAdvisor)
                .advisors(SessionAdvisors.session(conversationTurn.sessionId()))
                .advisors(advisorSpec -> advisorSpec
                        .param(SESSION_TURN_RECORD, conversationTurn.record())
                        .param(RESPONSE_METADATA_CAPTURE, responseMetadataCapture)
                )
                .toolContext(contextMap)
                .options(chatOptions(model, chatTimeout, correlationId, llmApiKey));

        if (StringUtils.isNotEmpty(attachmentContext)) {
            promptSpec = promptSpec.messages(new UserMessage(attachmentContext));
        }

        return capturingContentFlux(promptSpec.stream().chatResponse(), responseMetadataCapture, chatMessageService,
                liteLlmHeaderRegistry, since, correlationId, conversationTurn);
    }

    private Prompt privateSystemPrompt(UUID userId, UUID chatId, String message) {
        Address address = userPreferencesService.getAddress(userId);

        String templateAddress = Optional.ofNullable(address)
                .map(Object::toString)
                .orElse(AddressService.TEMPLATE_ADDRESS_NOT_FOUND);

        ZoneId zone = userPreferencesService.getZone(userId);
        String templateDateTime = ZonedDateTime.now(zone).format(CURRENT_DATE_TIME_FORMATTER);

        //Only this route carries memory. Tool routes keep their no-prose rule so it cannot leak into
        //tool arguments, and A2A agents are external.
        return systemPrompt(templateAddress, templateDateTime, userMemoryService.promptBlock(userId, chatId, message), SLASH_COMMAND_HINT);
    }

    /**
     * The default prompt without the sender's address, time zone or memory, which the whole room would
     * read in the reply, followed by the group-conversation addendum.
     */
    private Prompt sharedSystemPrompt(SharedPromptOptions shared) {
        Prompt base = systemPrompt(SharedPromptOptions.ADDRESS_WITHHELD,
                ZonedDateTime.now(ZoneOffset.UTC).format(CURRENT_DATE_TIME_FORMATTER), "", "");

        Prompt addendum = PromptTemplate.builder()
                .resource(sharedSystemPromptResource)
                .variables(Map.of("names", String.join(", ", shared.participantNames())))
                .build()
                .create();

        return new Prompt(base.getContents() + System.lineSeparator() + System.lineSeparator() + addendum.getContents());
    }

    private Prompt systemPrompt(String address, String dateTime, String memory, String slashCommandHint) {
        Map<String, Object> systemPromptContext = Map.of(
                AGENT_NAME, agentName,
                USER_ADDRESS, address,
                CURRENT_DATE_TIME, dateTime,
                USER_MEMORY, memory,
                "slashCommandHint", slashCommandHint
        );

        return PromptTemplate.builder()
                .resource(defaultSystemPromptResource)
                .variables(systemPromptContext)
                .build()
                .create();
    }

    private List<ToolCallback> privateToolCallbacks() {
        List<ToolCallback> toolCallbacks = new ArrayList<>(mcpIdentityProvider.getToolCallbacks(DEFAULT_PROMPT_TOOLS));
        toolCallbacks.add(localToolRegistry.callback(GENERATE_IMAGE));

        return toolCallbacks;
    }

    /**
     * Exactly the session's allowlist: the MCP tools it names, and {@code generate_image}, a local
     * tool, when it is listed.
     */
    private List<ToolCallback> sharedToolCallbacks() {
        Set<String> allowed = sharedSessionSettings.conversation().tools();

        Set<String> mcpTools = allowed.stream()
                .filter(tool -> !GENERATE_IMAGE.equals(tool))
                .collect(Collectors.toUnmodifiableSet());

        List<ToolCallback> toolCallbacks = new ArrayList<>(mcpIdentityProvider.getToolCallbacks(mcpTools));

        if (allowed.contains(GENERATE_IMAGE)) {
            toolCallbacks.add(localToolRegistry.callback(GENERATE_IMAGE));
        }

        return toolCallbacks;
    }

    /**
     * What a shared conversation's prompt differs by from a private one. Its presence is the switch:
     * the private route passes null.
     *
     * @param participantNames everyone currently seated, named to the model
     * @param senderName       who sent this message, which prefixes it for the model
     */
    record SharedPromptOptions(List<String> participantNames, String senderName) {
        static final String ADDRESS_WITHHELD = "Not shared in a group conversation.";
    }
}
