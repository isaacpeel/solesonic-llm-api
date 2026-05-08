package com.solesonic.mcp.client.prompt;

import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class McpPromptAdapter {

    /**
     * Converts an MCP GetPromptResult into a Spring AI Prompt.
     */
    public Prompt toPrompt(McpSchema.GetPromptResult getPromptResult) {
        if (getPromptResult == null || getPromptResult.messages() == null) {
            return new Prompt(List.of());
        }

        List<Message> springMessages = getPromptResult.messages().stream()
                .map(McpPromptAdapter::convertMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new Prompt(springMessages);
    }

    private static Message convertMessage(McpSchema.PromptMessage mcpMessage) {
        String textContent = extractText(mcpMessage.content());

        if (StringUtils.isEmpty(textContent)) {
            return null;
        }

        return switch (mcpMessage.role()) {
            case USER -> new UserMessage(textContent);
            case ASSISTANT -> new AssistantMessage(textContent);
        };
    }

    private static String extractText(McpSchema.Content content) {
        return switch (content) {
            case McpSchema.TextContent textMsg -> textMsg.text();
            case McpSchema.ImageContent imageMsg -> imageMsg.data();
            case null, default -> null;
        };
    }
}
