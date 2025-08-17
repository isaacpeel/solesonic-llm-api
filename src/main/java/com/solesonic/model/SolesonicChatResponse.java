package com.solesonic.model;

import com.solesonic.model.chat.history.ChatMessage;

import java.util.UUID;

public record SolesonicChatResponse(UUID id, ChatMessage message) {}
