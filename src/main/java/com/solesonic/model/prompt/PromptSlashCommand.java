package com.solesonic.model.prompt;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

public record PromptSlashCommand(String command, String name, String description) implements SlashCommand {

    public PromptSlashCommand(McpSchema.Prompt mcpPrompt) {
        this(
            mcpPrompt.meta().get(SlashCommand.COMMAND).toString(),
            mcpPrompt.name(),
            mcpPrompt.description()
        );
    }

    public Prompt buildPrompt(McpSchema.GetPromptResult getPromptResult, String userMessage) {
        List<Message> messages = getPromptResult.messages().stream()
                .map(mcpMessage -> (Message) switch (mcpMessage.role()) {
                    case USER -> new UserMessage(userMessage);
                    case ASSISTANT -> new AssistantMessage(extractText(mcpMessage.content()));
                })
                .toList();

        return new Prompt(messages);
    }

    private static String extractText(McpSchema.Content content) {
        return switch (content) {
            case McpSchema.TextContent textContent -> textContent.text();
            case McpSchema.ImageContent ignored -> "[Image content not supported]";
            default -> "";
        };
    }
}
