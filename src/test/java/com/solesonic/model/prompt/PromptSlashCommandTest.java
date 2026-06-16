package com.solesonic.model.prompt;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptSlashCommandTest {

    private final PromptSlashCommand command = new PromptSlashCommand("/ask", "ask", "Ask a question");

    @Test
    void buildPrompt_withUserRoleMessage_replacesContentWithUserMessage() {
        McpSchema.TextContent textContent = new McpSchema.TextContent(null, "original content", null);
        McpSchema.PromptMessage message = new McpSchema.PromptMessage(McpSchema.Role.USER, textContent);
        McpSchema.GetPromptResult result = new McpSchema.GetPromptResult(null, List.of(message), null);

        Prompt prompt = command.buildPrompt(result, "hello");

        List<Message> instructions = prompt.getInstructions();
        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(instructions.getFirst().getText()).isEqualTo("hello");
    }

    @Test
    void buildPrompt_withAssistantRoleAndTextContent_extractsText() {
        McpSchema.TextContent textContent = new McpSchema.TextContent(null, "assistant reply", null);
        McpSchema.PromptMessage message = new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, textContent);
        McpSchema.GetPromptResult result = new McpSchema.GetPromptResult(null, List.of(message), null);

        Prompt prompt = command.buildPrompt(result, "ignored");

        List<Message> instructions = prompt.getInstructions();
        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst()).isInstanceOf(AssistantMessage.class);
        assertThat(instructions.getFirst().getText()).isEqualTo("assistant reply");
    }


}
