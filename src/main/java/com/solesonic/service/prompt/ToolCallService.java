package com.solesonic.service.prompt;

import com.solesonic.model.chat.ResponseMetadataCapture;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ConversationSessionService;
import com.solesonic.service.chat.ConversationTurn;
import com.solesonic.tools.LocalToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.config.chat.SessionEventCorrelationAdvisor.SESSION_TURN_RECORD;

@Service
public class ToolCallService {
    private static final Logger log = LoggerFactory.getLogger(ToolCallService.class);

    @Value("classpath:prompts/task-prompt.st")
    private Resource taskPrompt;

    public static final String TASK_TOOL = "task_tool";

    private final McpSyncClient mcpClient;
    private final SlashCommandService slashCommandService;
    private final LocalToolRegistry localToolRegistry;
    private final ChatMessageService chatMessageService;
    private final ConversationSessionService conversationSessionService;

    public ToolCallService(McpSyncClient mcpClient,
                           SlashCommandService slashCommandService,
                           LocalToolRegistry localToolRegistry,
                           ChatMessageService chatMessageService,
                           ConversationSessionService conversationSessionService) {
        this.mcpClient = mcpClient;
        this.slashCommandService = slashCommandService;
        this.localToolRegistry = localToolRegistry;
        this.chatMessageService = chatMessageService;
        this.conversationSessionService = conversationSessionService;
    }

    public Flux<String> stream(UUID chatId,
                               String message,
                               ChatMessage inFlightMessage,
                               ToolSlashCommand toolCommand,
                               Map<String, Object> contextMap,
                               LlmApiKey llmApiKey) {

        ChatClient taskClient = slashCommandService.taskClient(toolCommand.callback(mcpClient), llmApiKey);

        return invoke(chatId, message, inFlightMessage, toolCommand.name(), taskClient, contextMap);
    }

    public Flux<String> streamLocal(UUID chatId,
                                    String message,
                                    ChatMessage inFlightMessage,
                                    LocalToolSlashCommand localToolCommand,
                                    Map<String, Object> contextMap,
                                    LlmApiKey llmApiKey) {

        ToolCallback toolCallback = localToolRegistry.callback(localToolCommand.name());
        ChatClient taskClient = slashCommandService.localTaskClient(toolCallback, llmApiKey);

        return invoke(chatId, message, inFlightMessage, localToolCommand.name(), taskClient, contextMap);
    }

    /**
     * A tool route gets no image prose, so no attachment context reaches its session. The call is
     * blocking: the turn ends when it returns, or is abandoned when it throws.
     */
    private Flux<String> invoke(UUID chatId,
                                String message,
                                ChatMessage inFlightMessage,
                                String toolName,
                                ChatClient taskClient,
                                Map<String, Object> contextMap) {

        log.info("Tool invoke: {}", toolName);

        //Taken before the call, so the lookup that attaches the turn's accounting finds the row this
        //turn's completion writes rather than the previous turn's.
        ZonedDateTime since = ZonedDateTime.now();

        SystemPromptTemplate taskSystemPromptTemplate = new SystemPromptTemplate(taskPrompt);
        Prompt prompt = taskSystemPromptTemplate.create(Map.of(TASK_TOOL, toolName));

        ConversationTurn conversationTurn = conversationSessionService.prepareTurn(chatId, inFlightMessage, null);

        ChatResponse chatResponse;

        try {
            chatResponse = taskClient.prompt(prompt)
                    .user(message)
                    .advisors(SessionAdvisors.session(conversationTurn.sessionId()))
                    .advisors(advisorSpec -> advisorSpec.param(SESSION_TURN_RECORD, conversationTurn.record()))
                    .toolContext(contextMap)
                    .call()
                    .chatResponse();
        } catch (RuntimeException runtimeException) {
            conversationTurn.abandon();
            throw runtimeException;
        }

        if (chatResponse == null || chatResponse.getResult() == null) {
            log.warn("No response received for tool: {}", toolName);
            conversationTurn.complete(null);
            return Flux.empty();
        }

        ChatStreamSupport.recordFinishReasons(chatResponse, conversationTurn.record());

        String result = chatResponse.getResult().getOutput().getText();

        conversationTurn.complete(result);

        //Ahead of the blank-answer return on purpose: a blank answer still cost the tokens it cost,
        //and whether the model ran is a different question from whether it said anything. One
        //accept is enough — .call() hands back a response with both the answer and the final usage,
        //and the capture closes a call the moment it sees a non-empty usage.
        ResponseMetadataCapture responseMetadataCapture = new ResponseMetadataCapture();
        responseMetadataCapture.accept(chatResponse);

        ChatStreamSupport.persist(responseMetadataCapture, chatMessageService, chatId, since);

        if (StringUtils.isBlank(result)) {
            log.warn("Empty result for tool: {}", toolName);
            return Flux.empty();
        }

        return Flux.just(result);
    }
}
