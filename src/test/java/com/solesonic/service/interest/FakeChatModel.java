package com.solesonic.service.interest;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * A model that answers with fixed JSON, written with single quotes for readability, and records every
 * prompt it was given. Nothing in the interest tests calls a real model.
 */
final class FakeChatModel implements ChatModel {
    final List<Prompt> prompts = new ArrayList<>();
    private String response;
    private RuntimeException failure;

    void respond(String singleQuotedJson) {
        this.response = singleQuotedJson.replace('\'', '"');
    }

    void fail(RuntimeException failure) {
        this.failure = failure;
    }

    String lastUserText() {
        return prompts.getLast().getUserMessage().getText();
    }

    String lastSystemText() {
        return prompts.getLast().getSystemMessage().getText();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);

        if (failure != null) {
            throw failure;
        }

        return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
    }
}
