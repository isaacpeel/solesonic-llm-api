package com.solesonic.service.prompt;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.util.AttachmentContextFormatter;
import com.solesonic.util.AuthenticationTokens;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.config.chat.ChatConfig.DEFAULT_CHAT_CLIENT;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_TOKEN;
import static com.solesonic.service.prompt.ChatStreamSupport.chatOptions;
import static com.solesonic.service.prompt.ChatStreamSupport.contentFlux;
import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * Where every incoming chat message is routed.
 * <p>
 * Only two decisions are made here — whether the send named a slash command, and, when it did not,
 * whether the conversation is pinned to a remote agent. Everything each branch then does belongs to
 * a collaborator: {@link AttachmentContextResolver} for what the attachments amount to,
 * {@link SlashCommandRouter} for the four command routes. What is left in this class is the
 * no-command default LLM path, which is its actual reason for existing.
 */
@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    public static final String CHAT_ID = "chatId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String PROGRESS_TOKEN = "progressToken";
    public static final String AGENT_NAME = "agentName";

    private final ChatClient chatClient;
    private final SlashCommandService slashCommandService;
    private final SlashCommandRouter slashCommandRouter;
    private final AttachmentContextResolver attachmentContextResolver;
    private final A2AAgentService a2aAgentService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final VectorStoreService vectorStoreService;

    private final String defaultChatModel;

    private final Prompt defaultSystemPrompt;

    public PromptService(
            @Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
            SlashCommandService slashCommandService,
            SlashCommandRouter slashCommandRouter,
            AttachmentContextResolver attachmentContextResolver,
            A2AAgentService a2aAgentService,
            A2AStickyAgentService a2aStickyAgentService,
            VectorStoreService vectorStoreService,
            @Value("${solesonic.llm.bot.name}") String agentName,
            @Value("${spring.ai.openai.model}") String defaultChatModel,
            @Value("classpath:prompts/basic-system-prompt.st") Resource defaultSystemPromptResource) {
        this.chatClient = chatClient;
        this.slashCommandService = slashCommandService;
        this.slashCommandRouter = slashCommandRouter;
        this.attachmentContextResolver = attachmentContextResolver;
        this.a2aAgentService = a2aAgentService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.vectorStoreService = vectorStoreService;
        this.defaultChatModel = defaultChatModel;

        Map<String, Object> systemPromptContext = Map.of(
                AGENT_NAME, agentName
        );

        PromptTemplate promptTemplate = PromptTemplate.builder()
                .resource(defaultSystemPromptResource)
                .variables(systemPromptContext)
                .build();

        defaultSystemPrompt = promptTemplate.create();
    }

    public Flux<String> stream(UUID chatId,
                               UUID userId,
                               ChatRequest chatMessage,
                               Authentication authentication) {
        log.info("Streaming prompt for chat id {}", chatId);

        String authToken = AuthenticationTokens.token(authentication);

        AttachmentResolution attachments = attachmentContextResolver.resolve(chatId, userId, chatMessage.attachmentIds());

        Map<String, Object> contextMap = Map.of(
                USER_TOKEN, authToken,
                USER_ID, userId,
                CHAT_ID, chatId,
                PROGRESS_TOKEN, chatId);

        Set<String> commands = chatMessage.commands();

        if (CollectionUtils.isEmpty(commands)) {
            return streamWithoutCommand(chatId, userId, chatMessage.chatMessage(), attachments, contextMap, authToken);
        }

        SlashCommand slashCommand = slashCommandService.commands(commands).stream()
                .findFirst()
                .orElseThrow(IllegalStateException::new);

        return slashCommandRouter.route(slashCommand, chatId, userId, chatMessage.chatMessage(), attachments, contextMap, authToken);
    }

    /**
     * The default route: a conversation already pinned to a remote agent keeps going there, and
     * everything else reaches the LLM.
     * <p>
     * The sticky check is a Redis read, so the branch is taken inside the stream rather than before
     * it.
     */
    private Flux<String> streamWithoutCommand(UUID chatId,
                                              UUID userId,
                                              String message,
                                              AttachmentResolution attachments,
                                              Map<String, Object> contextMap,
                                              String authToken) {
        return a2aStickyAgentService
                .getActiveAgent(chatId)
                .flatMapMany(stickyAgent -> {
                    if (stickyAgent.isPresent()) {
                        log.info("Routing to sticky A2A agent '{}' for chat {}", stickyAgent.get(), chatId);

                        //A remote agent takes a single string, so the block is inlined here rather
                        //than carried as a message of its own.
                        return a2aAgentService.delegate(chatId, stickyAgent.get(),
                                AttachmentContextFormatter.prepend(message, attachments.imageDescriptions()),
                                authToken);
                    }

                    log.info("No command or sticky agent, using default system prompt.");

                    return streamDefaultSystemPrompt(chatId, userId, message, attachments.attachmentContext(), contextMap, defaultChatModel);
                });
    }

    /**
     * Streams the no-slash-command, no-sticky-agent chat path: the default system prompt — built
     * once in the constructor from {@code basic-system-prompt.st} and the configured agent name,
     * not rebuilt on every call — plus the RAG retrieval advisor and the user's message.
     *
     * @param chatId       identifies the conversation to the retrieval advisor, both directly and
     *                     as the {@code CONVERSATION_ID} advisor param
     * @param userId       resolves the retrieval advisor's per-user similarity threshold
     * @param message      the user's own words
     * @param attachmentContext the attachment block — described images, named documents, or both —
     *                     or null when nothing was attached. Carried as a message of its own, which
     *                     {@code DefaultChatClientUtils} places between the system text and the user
     *                     message, and which the retrieval advisor leaves alone because it only ever
     *                     rewrites the last user message
     * @param contextMap   tool context forwarded to any MCP tool the model calls mid-turn
     * @param model        the chat model name requested via {@code OpenAiChatOptions}
     */
    private Flux<String> streamDefaultSystemPrompt(UUID chatId,
                                                   UUID userId,
                                                   String message,
                                                   String attachmentContext,
                                                   Map<String, Object> contextMap,
                                                   String model) {

        var promptSpec = chatClient.prompt()
                .system(defaultSystemPrompt.getContents())
                .user(message)
                .advisors(vectorStoreService.retrievalAugmentationAdvisor(userId, chatId))
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .toolContext(contextMap)
                .options(chatOptions(model));

        if (StringUtils.isNotEmpty(attachmentContext)) {
            promptSpec = promptSpec.messages(new UserMessage(attachmentContext));
        }

        return contentFlux(promptSpec.stream().chatResponse());
    }
}
