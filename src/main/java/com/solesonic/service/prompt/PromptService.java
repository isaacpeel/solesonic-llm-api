package com.solesonic.service.prompt;

import com.solesonic.mcp.client.prompt.McpPromptAdapter;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.ResponseMetadataCapture;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.prompt.*;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.etl.ChatDocumentIngestionService;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.vision.ImageDescriptionService;
import com.solesonic.util.AttachmentContextFormatter;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;

import static com.solesonic.config.chat.ChatConfig.DEFAULT_CHAT_CLIENT;
import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
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
    private final SlashCommandService slashCommandService;
    private final McpSyncClient mcpClient;
    private final McpPromptAdapter mcpPromptAdapter;
    private final ToolCallService toolCallService;
    private final A2AAgentService a2aAgentService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final VectorStoreService vectorStoreService;
    private final ImageDescriptionService imageDescriptionService;
    private final ChatAttachmentService chatAttachmentService;
    private final ChatDocumentIngestionService chatDocumentIngestionService;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    @Value("classpath:prompts/basic-system-prompt.st")
    private Resource basicSystemPrompt;

    @Value("${spring.ai.openai.model}")
    private String defaultChatModel;

    public PromptService(
            @Qualifier(DEFAULT_CHAT_CLIENT) ChatClient chatClient,
            SlashCommandService slashCommandService,
            McpSyncClient mcpClient,
            McpPromptAdapter mcpPromptAdapter,
            ToolCallService toolCallService,
            A2AAgentService a2aAgentService,
            A2AStickyAgentService a2aStickyAgentService,
            VectorStoreService vectorStoreService,
            ImageDescriptionService imageDescriptionService,
            ChatAttachmentService chatAttachmentService,
            ChatDocumentIngestionService chatDocumentIngestionService) {
        this.chatClient = chatClient;
        this.slashCommandService = slashCommandService;
        this.mcpClient = mcpClient;
        this.mcpPromptAdapter = mcpPromptAdapter;
        this.toolCallService = toolCallService;
        this.a2aAgentService = a2aAgentService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.vectorStoreService = vectorStoreService;
        this.imageDescriptionService = imageDescriptionService;
        this.chatAttachmentService = chatAttachmentService;
        this.chatDocumentIngestionService = chatDocumentIngestionService;
    }

    /**
     * @param responseMetadataCapture receives what the model server reports about the turn, response
     *                                by response, for as many model calls as the turn makes. Left
     *                                unset by routes that call no chat model — an A2A agent
     *                                delegation — so a caller reading it after the stream finishes
     *                                must treat {@code null} as "no metadata available" rather than
     *                                a bug.
     */
    public Flux<String> stream(UUID chatId, UUID userId, ChatRequest chatMessage, Authentication authentication,
                               ResponseMetadataCapture responseMetadataCapture) {
        log.info("Streaming prompt for chat id {}", chatId);
        String message = chatMessage.chatMessage();
        Set<String> commands = chatMessage.commands();

        ChatAttachmentService.AttachmentPartition attachments = chatAttachmentService
                .partition(userId, chatMessage.attachmentIds());

        List<ChatAttachmentDescription> imageDescriptions = imageDescriptionService
                .describe(chatId, userId, attachments.imageIds());

        List<String> indexedDocuments = chatDocumentIngestionService
                .ingest(chatId, userId, attachments.documentIds());

        String attachmentContext = attachmentContext(imageDescriptions, indexedDocuments);

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof Jwt jwt)) {
            throw new IllegalStateException("Authentication principal is not a JWT token");
        }

        String authToken = jwt.getTokenValue();

        Map<String, Object> contextMap = Map.of(
                USER_TOKEN, authToken,
                USER_ID, userId,
                CHAT_ID, chatId,
                PROGRESS_TOKEN, chatId);

        if (CollectionUtils.isEmpty(commands)) {
            return a2aStickyAgentService
                    .getActiveAgent(chatId)
                    .flatMapMany(stickyAgent -> {
                        if (stickyAgent.isPresent()) {
                            log.info("Routing to sticky A2A agent '{}' for chat {}", stickyAgent.get(), chatId);

                            return a2aAgentService.delegate(chatId, stickyAgent.get(),
                                    AttachmentContextFormatter.prepend(message, imageDescriptions), authToken);
                        }

                        log.info("No command or sticky agent, using basic-prompt from MCP.");

                        return streamBasicPrompt(chatId, userId, message, attachmentContext, contextMap, defaultChatModel, responseMetadataCapture);
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

                Prompt prompt = promptCommand.buildPrompt(getPromptResult, message, attachmentContext);

                Flux<ChatResponse> promptChatResponse = chatClient.prompt(prompt)
                        .advisors(vectorStoreService.retrievalAugmentationAdvisor(userId, chatId))
                        .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, chatId))
                        .toolContext(contextMap)
                        .options(chatOptions(defaultChatModel))
                        .stream()
                        .chatResponse();

                yield a2aStickyAgentService.deactivate(chatId)
                        .thenMany(contentFlux(promptChatResponse, responseMetadataCapture));
            }

            case ToolSlashCommand toolCommand -> a2aStickyAgentService.deactivate(chatId)
                    .thenMany(toolCallService.stream(chatId, message, toolCommand, contextMap, responseMetadataCapture));

            case LocalToolSlashCommand localToolCommand -> a2aStickyAgentService.deactivate(chatId)
                    .thenMany(toolCallService.streamLocal(chatId, message, localToolCommand, contextMap, responseMetadataCapture));

            case AgentSlashCommand agentCommand -> {
                log.info("A2A agent invoke: {}", agentCommand.command());

                yield a2aStickyAgentService.activate(chatId, agentCommand.command())
                        .thenMany(a2aAgentService.delegate(chatId, agentCommand.command(),
                                AttachmentContextFormatter.prepend(message, imageDescriptions), authToken));
            }
        };
    }

    /**
     * Joins what the model is told about this message's attachments into one block, documents
     * first: the document note only says which files exist, while the image block carries actual
     * content, and the content reads better closest to the question.
     *
     * @return the block, or null when the message carried no attachment either pass could use
     */
    private static String attachmentContext(List<ChatAttachmentDescription> imageDescriptions,
                                            List<String> indexedDocuments) {
        String imageContext = AttachmentContextFormatter.context(imageDescriptions);
        String documentContext = AttachmentContextFormatter.documentContext(indexedDocuments);

        if (documentContext == null) {
            return imageContext;
        }

        if (imageContext == null) {
            return documentContext;
        }

        return documentContext + System.lineSeparator() + imageContext;
    }

    /**
     * @param message      the user's own words — both the MCP prompt template and the model get these
     *                     unaltered
     * @param attachmentContext the attachment block — described images, named documents, or both —
     *                     or null when nothing was attached. Carried as a message of its own, which
     *                     {@code DefaultChatClientUtils} places between the system text and the user
     *                     message, and which the retrieval advisor leaves alone because it only ever
     *                     rewrites the last user message
     */
    private Flux<String> streamBasicPrompt(UUID chatId,
                                           UUID userId,
                                           String message,
                                           String attachmentContext,
                                           Map<String, Object> contextMap,
                                           String model,
                                           ResponseMetadataCapture responseMetadataCapture) {
        Map<String, Object> promptContext = Map.of(
                AGENT_NAME, agentName
        );

        PromptTemplate promptTemplate = PromptTemplate.builder()
                .resource(basicSystemPrompt)
                .variables(promptContext)
                .build();

        Prompt systemPrompt = promptTemplate.create();

        var promptSpec = chatClient.prompt()
                .system(systemPrompt.getContents())
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

        return contentFlux(promptSpec.stream().chatResponse(), responseMetadataCapture);
    }

    /**
     * Asks the server for {@code stream_options.include_usage}, which is what puts the turn's token
     * counts on the stream's final chunk at all.
     * <p>
     * Pinning it rather than relying on the default is deliberate. Spring AI only defaults it to true
     * while no stream options are set: the moment anything sets one,
     * {@code OpenAiChatModel.createRequest} reads {@code includeUsage} out of it and a null there
     * becomes {@code false}. Setting any unrelated stream option elsewhere would otherwise silently
     * take the token counts away again.
     */
    private static OpenAiChatOptions.Builder chatOptions(String model) {
        return OpenAiChatOptions.builder()
                .model(model)
                .streamUsage(true);
    }

    /**
     * Equivalent to {@code StreamResponseSpec.content()}, plus offering <em>every</em> response to
     * the capture on the way past. No single response holds the turn's accounting: the model name and
     * finish reason ride the chunk that carries the text, the token counts arrive on a later chunk
     * with no text at all, and a tool-calling turn repeats both once per round trip. This must run
     * over the same stream that produces the chunks, not a second call, or the model would be invoked
     * twice.
     */
    private static Flux<String> contentFlux(Flux<ChatResponse> chatResponseFlux,
                                            ResponseMetadataCapture responseMetadataCapture) {
        return chatResponseFlux
                .doOnNext(responseMetadataCapture::accept)
                .map(chatResponse -> Optional.ofNullable(chatResponse.getResult())
                        .map(Generation::getOutput)
                        .map(AbstractMessage::getText)
                        .orElse(""))
                .filter(StringUtils::isNotEmpty);
    }
}
