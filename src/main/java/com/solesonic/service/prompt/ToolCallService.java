package com.solesonic.service.prompt;

import com.solesonic.model.prompt.ToolSlashCommand;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
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

    public static final String TASK_TOOL   = "task_tool";

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
                               Map<String, Object> contextMap,
                               Advisor retrievalAugmentationAdvisor) {

        log.info("Tool invoke: {}", toolCommand.name());

        SystemPromptTemplate taskSystemPromptTemplate = new SystemPromptTemplate(taskPrompt);

        Map<String, Object> promptInputs = Map.of(TASK_TOOL, toolCommand.name());

        Prompt prompt = taskSystemPromptTemplate.create(promptInputs);

        ChatClient taskClient = slashCommandService.taskClient(toolCommand.callback(mcpClient));

        return taskClient.prompt(prompt)
                .user(message)
                .advisors(advisorSpec -> advisorSpec
                        .param(CONVERSATION_ID, chatId)
                )
                .advisors(retrievalAugmentationAdvisor)
                .tools(toolSpec -> toolSpec.context(contextMap))
                .stream()
                .content();
    }
}
