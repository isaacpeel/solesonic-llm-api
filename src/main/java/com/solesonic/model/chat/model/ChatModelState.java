package com.solesonic.model.chat.model;

/**
 * Which model a chat's next turn uses.
 * <p>
 * {@code selectedModel} is null when the chat follows {@code defaultModel}. {@code activeAgent} is the
 * sticky A2A agent holding the chat, if any: while one does, turns bypass the chat model, so a
 * selection takes effect only once the agent is released.
 */
public record ChatModelState(String defaultModel, String selectedModel, String activeAgent) {
}
