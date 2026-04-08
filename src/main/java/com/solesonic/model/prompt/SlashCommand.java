package com.solesonic.model.prompt;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;
import java.util.Map;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SlashCommand {
    public static final String COMMAND = "command";
    public static final String PROMPT = "prompt";
    public static final String TOOL = "tool";

    public String command;
    public String name;
    public String description;
    public String commandType;
    public Map<String, Object> meta;

    public Prompt prompt;
    public McpSchema.Tool tool;
    public OllamaChatOptions options;

    public SlashCommand() {
    }

    public SlashCommand(McpSchema.Prompt prompt) {
        Map<String, Object> meta = prompt.meta();
        String name = prompt.name();
        String description = prompt.description();

        String command = meta.get(COMMAND).toString();

        this.name = name;
        this.description = description;
        this.command = command;


    }

    public SlashCommand(McpSchema.Tool tool) {
        Map<String, Object> meta = tool.meta();
        String name = tool.name();
        String description = tool.description();

        String command = meta.get(COMMAND).toString();

        this.commandType = TOOL;
        this.name = name;
        this.description = description;
        this.command = command;
        this.tool = tool;
    }

    public Prompt preparePrompt(McpSchema.GetPromptResult getPromptResult, String message) {
        List<Message> messages = getPromptResult.messages().stream()
                .map(mcpMessage -> {
                    if (mcpMessage instanceof McpSchema.PromptMessage(McpSchema.Role role, McpSchema.Content content)) {
                        String textContent = extractTextContent(content);

                        return (Message) switch (role) {
                            case USER -> new org.springframework.ai.chat.messages.UserMessage(message);
                            case ASSISTANT -> new org.springframework.ai.chat.messages.AssistantMessage(textContent);
                        };
                    }
                    throw new IllegalArgumentException("Unexpected message type.");
                })
                .toList();

        return new Prompt(messages);
    }

    private String extractTextContent(Object content) {
        return switch (content) {
            case String text -> text;
            case McpSchema.TextContent textContent -> textContent.text();
            case McpSchema.ImageContent _ -> "[Image content not supported]";
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

    public String command() {
        return command;
    }

    public String name() {
        return name;
    }

    public McpSchema.Tool tool() {
        return tool;
    }

    public Prompt prompt() {
        return prompt;
    }
}
