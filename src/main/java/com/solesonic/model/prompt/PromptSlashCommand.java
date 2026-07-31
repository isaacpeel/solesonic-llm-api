package com.solesonic.model.prompt;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

public record PromptSlashCommand(String command, String name, String description) implements SlashCommand {

    public PromptSlashCommand(McpSchema.Prompt mcpPrompt) {
        this(
            mcpPrompt.name(),
            mcpPrompt.name(),
            mcpPrompt.description()
        );
    }

    /**
     * @param imageContext the described-images block, or null when no image was attached. Inserted
     *                     ahead of each user message rather than merged into it, so the retrieval
     *                     advisor — which rewrites only the last user message — cannot absorb it
     */
    public Prompt buildPrompt(McpSchema.GetPromptResult getPromptResult, String userMessage, String imageContext) {
        List<Message> messages = new ArrayList<>(getPromptResult.messages().size());

        for (McpSchema.PromptMessage mcpMessage : getPromptResult.messages()) {
            switch (mcpMessage.role()) {
                case USER -> {
                    if (imageContext != null) {
                        messages.add(new UserMessage(imageContext));
                    }

                    messages.add(new UserMessage(userMessage));
                }
                case ASSISTANT -> messages.add(new AssistantMessage(extractText(mcpMessage.content())));
            }
        }

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
