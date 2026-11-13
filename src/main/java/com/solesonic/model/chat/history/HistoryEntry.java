package com.solesonic.model.chat.history;

import org.springframework.ai.chat.messages.Message;

/**
 * One {@code chat_message} row as the model reads it.
 */
public record HistoryEntry(ChatMessage chatMessage, Message message) {
}
