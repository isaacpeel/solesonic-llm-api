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
                .map(mcpMessage -> {
                    if (mcpMessage instanceof McpSchema.PromptMessage(McpSchema.Role role, McpSchema.Content content)) {
                        return (Message) switch (role) {
                            case USER -> new UserMessage(userMessage);
                            case ASSISTANT -> new AssistantMessage(extractText(content));
                        };
                    }
                    throw new IllegalArgumentException("Unexpected message type.");
                })
                .toList();

        return new Prompt(messages);
    }

    private static String extractText(Object content) {
        return switch (content) {
            case String text -> text;
            case McpSchema.TextContent textContent -> textContent.text();
            case McpSchema.ImageContent ignored -> "[Image content not supported]";
            case List<?> contentList -> contentList.stream()
                    .map(item -> switch (item) {
                        case McpSchema.TextContent textContent -> textContent.text();
                        case String text -> text;
                        default -> "";
                    })
                    .filter(entry -> !entry.isEmpty())
                    .reduce("", (accumulated, next) -> accumulated + "\n" + next);
            default -> "";
        };
    }
}
