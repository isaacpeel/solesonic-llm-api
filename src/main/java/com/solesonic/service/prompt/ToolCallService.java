package com.solesonic.service.prompt;

import com.solesonic.model.prompt.ToolSlashCommand;
import io.modelcontextprotocol.client.McpSyncClient;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
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
                .toolContext(contextMap)
                .call()
                .chatResponse();

        if (chatResponse == null || chatResponse.getResult() == null) {
            log.warn("No response received for tool: {}", toolCommand.name());
            return Flux.empty();
        }

        String result = chatResponse.getResult().getOutput().getText();

        if (StringUtils.isBlank(result)) {
            log.warn("Empty result for tool: {}", toolCommand.name());
            return Flux.empty();
        }

        return Flux.just(result);
    }
}
