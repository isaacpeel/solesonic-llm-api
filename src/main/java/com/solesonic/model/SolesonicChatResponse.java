package com.solesonic.model;

import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.history.ChatMessage;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record SolesonicChatResponse(UUID id, ChatMessage message, @Nullable ResponseMetadata responseMetadata) {

    public SolesonicChatResponse(UUID id, ChatMessage message) {
        this(id, message, null);
    }
}
