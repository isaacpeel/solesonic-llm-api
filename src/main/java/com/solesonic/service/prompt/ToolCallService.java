package com.solesonic.service.prompt;

import com.solesonic.mcp.client.IdentityToolCallback;
import com.solesonic.model.prompt.ToolSlashCommand;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
public class ToolCallService {
    private static final Logger log = LoggerFactory.getLogger(ToolCallService.class);

    @Value("classpath:prompts/task-prompt.st")
    private Resource taskPrompt;

    public static final String TASK_TOOL = "task_tool";

    private final McpSyncClient mcpClient;
    private final SlashCommandService slashCommandService;

    public ToolCallService(McpSyncClient mcpClient,
                           SlashCommandService slashCommandService) {
        this.mcpClient = mcpClient;
        this.slashCommandService = slashCommandService;
    }

    public Flux<String> stream(UUID chatId,
                               String message,
                               ToolSlashCommand toolCommand,
                               Map<String, Object> contextMap) {

        log.info("Tool invoke: {}", toolCommand.name());

        ToolCallback toolCallback = toolCommand.callback(mcpClient);
        ChatClient taskClient = slashCommandService.taskClient(toolCallback);

        SystemPromptTemplate taskSystemPromptTemplate = new SystemPromptTemplate(taskPrompt);
        Prompt prompt = taskSystemPromptTemplate.create(Map.of(TASK_TOOL, toolCommand.name()));

        ChatResponse chatResponse = taskClient.prompt(prompt)
                .user(message)
                .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, chatId))
                .tools(toolSpec -> toolSpec.context(contextMap))
                .call()
                .chatResponse();

        assert chatResponse != null;
        List<AssistantMessage.ToolCall> toolCalls = Objects.requireNonNull(chatResponse.getResult()).getOutput().getToolCalls();

        if (toolCalls.isEmpty()) {
            log.warn("Model did not emit a tool call for: {}", toolCommand.name());
            return Flux.empty();
        }

        AssistantMessage.ToolCall toolCall = toolCalls.getFirst();

        log.info("Executing tool call: {}", toolCall.name());

        IdentityToolCallback identityToolCallback = new IdentityToolCallback(toolCallback);
        ToolContext toolContext = new ToolContext(contextMap);
        String result = identityToolCallback.call(toolCall.arguments(), toolContext);

        return Flux.just(result);
    }
}
