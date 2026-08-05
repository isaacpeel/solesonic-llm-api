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

        Prompt prompt = command.buildPrompt(result, "hello", null);

        List<Message> instructions = prompt.getInstructions();
        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(instructions.getFirst().getText()).isEqualTo("hello");
    }

    /**
     * The image block has to stay a message of its own, ahead of the user's words: the retrieval
     * advisor rewrites the last user message, so anything merged into it is wrapped in retrieved
     * context and told to answer from that context alone.
     */
    @Test
    void buildPrompt_withImageContext_addsItAsItsOwnMessageBeforeTheUserMessage() {
        McpSchema.TextContent textContent = new McpSchema.TextContent(null, "original content", null);
        McpSchema.PromptMessage message = new McpSchema.PromptMessage(McpSchema.Role.USER, textContent);
        McpSchema.GetPromptResult result = new McpSchema.GetPromptResult(null, List.of(message), null);

        Prompt prompt = command.buildPrompt(result, "what is this?", "Image 1 — screenshot.png:\na login screen");

        List<Message> instructions = prompt.getInstructions();
        assertThat(instructions).hasSize(2);
        assertThat(instructions.getFirst().getText()).contains("a login screen");
        assertThat(instructions.getLast().getText()).isEqualTo("what is this?");
    }

    @Test
    void buildPrompt_withAssistantRoleAndTextContent_extractsText() {
        McpSchema.TextContent textContent = new McpSchema.TextContent(null, "assistant reply", null);
        McpSchema.PromptMessage message = new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, textContent);
        McpSchema.GetPromptResult result = new McpSchema.GetPromptResult(null, List.of(message), null);

        Prompt prompt = command.buildPrompt(result, "ignored", null);

        List<Message> instructions = prompt.getInstructions();
        assertThat(instructions).hasSize(1);
        assertThat(instructions.getFirst()).isInstanceOf(AssistantMessage.class);
        assertThat(instructions.getFirst().getText()).isEqualTo("assistant reply");
    }


}
