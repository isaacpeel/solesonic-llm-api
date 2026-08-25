package com.solesonic.service.prompt;

import com.solesonic.mcp.client.prompt.McpPromptAdapter;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.etl.ChatDocumentIngestionService;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.service.vision.ImageDescriptionService;
import com.solesonic.util.AttachmentContextFormatter;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
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
    private final UserPreferencesService userPreferencesService;
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
            ImageDescriptionService imageDescriptionService,
            ChatAttachmentService chatAttachmentService,
            ChatDocumentIngestionService chatDocumentIngestionService) {
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
        this.chatAttachmentService = chatAttachmentService;
        this.chatDocumentIngestionService = chatDocumentIngestionService;
    }

    public String model(UUID userId) {
        return userPreferencesService.get(userId).getModel();
    }

    public Flux<String> stream(UUID chatId, UUID userId, ChatRequest chatMessage, Authentication authentication) {
        log.info("Streaming prompt for chat id {}", chatId);
        String model = model(userId);
        String message = chatMessage.chatMessage();
        Set<String> commands = chatMessage.commands();

        // The two kinds of attachment are handled by different passes, and each pass owns the SSE
        // events for the ids it is given, so the split has to happen before either runs.
        ChatAttachmentService.AttachmentPartition attachments = chatAttachmentService
                .partition(userId, chatMessage.attachmentIds());

        // Image attachments reach the model as text: descriptions produced by a separate vision
        // model. They travel as their own message next to the user's, never folded into it — the
        // retrieval advisor rewrites the last user message, wrapping it in retrieved documents and
        // an instruction to answer from those alone, and anything inside that wrapper competes with
        // the documents instead of standing beside them. Tool routes carry no attachment context at
        // all — see the ToolSlashCommand branch below.
        List<ChatAttachmentDescription> imageDescriptions = imageDescriptionService
                .describe(chatId, userId, attachments.imageIds());

        // Documents are indexed rather than inlined. A document does not fit in a prompt the way a
        // description does, so its text is split and embedded at conversation scope, and reaches
        // the model through the same retrieval that serves the global knowledge base — only the
        // passages bearing on the question ever enter the context window. What the model is told
        // directly is merely that the documents exist.
        List<String> indexedDocuments = chatDocumentIngestionService
                .ingest(chatId, userId, attachments.documentIds());

        String attachmentContext = attachmentContext(imageDescriptions, indexedDocuments);

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof Jwt jwt)) {
            throw new IllegalStateException("Authentication principal is not a JWT token");
        }

        String authToken = jwt.getTokenValue();

        //userId rides along for tool results that persist something on the user's behalf — a
        //generated image is owned by whoever asked for it. IdentityToolCallback strips it, like the
        //token, before the call leaves for the MCP server.
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

                            //The remote agent takes a single string and has no message structure to
                            //hold a separate block, so this is the one route that still inlines the
                            //descriptions — and the only way it learns an image was attached at all.
                            return a2aAgentService.delegate(chatId, stickyAgent.get(),
                                    AttachmentContextFormatter.prepend(message, imageDescriptions), authToken);
                        }

                        log.info("No command or sticky agent, using basic-prompt from MCP.");

                        return streamBasicPrompt(chatId, userId, message, attachmentContext, contextMap, model);
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

                //The MCP call above renders a prompt template and wants the user's actual words; the
                //prompt sent to the model carries the image descriptions too, as their own message
                //ahead of the user's rather than mixed into it.
                Prompt prompt = promptCommand.buildPrompt(getPromptResult, message, attachmentContext);

                yield a2aStickyAgentService.deactivate(chatId)
                        .thenMany(chatClient.prompt(prompt)
                                .advisors(vectorStoreService.retrievalAugmentationAdvisor(userId, chatId))
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
                                           String model) {
        String systemText = loadBasicPromptSystemText(message);

        var promptSpec = chatClient.prompt()
                .advisors(vectorStoreService.retrievalAugmentationAdvisor(userId, chatId))
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .toolContext(contextMap)
                .options(OllamaChatOptions.builder().model(model));

        //A UserMessage rather than a SystemMessage on purpose: MessageChatMemoryAdvisor hoists every
        //system message to the front of the prompt, which would move this away from the message it
        //describes and behind the whole conversation history.
        if (StringUtils.isNotEmpty(attachmentContext)) {
            promptSpec = promptSpec.messages(new UserMessage(attachmentContext));
        }

        promptSpec = promptSpec.user(message);

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
