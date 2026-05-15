package com.solesonic.model.prompt;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import io.a2a.spec.AgentCard;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SlashCommand {
    public static final String COMMAND = "command";
    public static final String PROMPT = "prompt";
    public static final String AGENT = "agent";

    public String command;
    public String name;
    public String description;
    public String commandType;
    public Map<String, Object> meta;

    public Prompt prompt;

    @SuppressWarnings("unused")
    public SlashCommand() {
    }

    public SlashCommand(McpSchema.Prompt prompt) {
        Map<String, Object> meta = prompt.meta();
        String name = prompt.name();
        String description = prompt.description();

        String command = meta.get(COMMAND).toString();

        this.commandType = PROMPT;
        this.name = name;
        this.description = description;
        this.command = command;
    }

    public SlashCommand(AgentCard agentCard) {
        this.commandType = AGENT;
        this.command = agentCard.name();
        this.name = agentCard.name();
        this.description = agentCard.description();
        this.meta = Map.of(COMMAND, agentCard.name());
    }

    public Prompt preparePrompt(McpSchema.GetPromptResult getPromptResult, String message) {
        List<Message> messages = getPromptResult.messages().stream()
                .map(mcpMessage -> {
                    if (mcpMessage instanceof McpSchema.PromptMessage(McpSchema.Role role, McpSchema.Content content)) {
                        String textContent = extractTextContent(content);

                        return (Message) switch (role) {
                            case USER -> new UserMessage(message);
                            case ASSISTANT -> new AssistantMessage(textContent);
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

    public Prompt prompt() {
        return prompt;
    }
}
