package com.solesonic.model.chat.model;

import java.util.List;

/**
 * What a model picker needs: the endpoint's live list, and the chat's state when a chat was named.
 * A client marks {@code selectedModel ?? defaultModel} as current.
 */
public record ChatModelOptions(List<AvailableModel> models,
                               String defaultModel,
                               String selectedModel,
                               String activeAgent) {

    public static ChatModelOptions of(List<AvailableModel> models, ChatModelState chatModelState) {
        return new ChatModelOptions(models,
                chatModelState.defaultModel(),
                chatModelState.selectedModel(),
                chatModelState.activeAgent());
    }
}
