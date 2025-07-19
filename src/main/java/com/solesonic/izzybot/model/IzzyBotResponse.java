package com.solesonic.izzybot.model;

import com.solesonic.izzybot.model.chat.history.ChatMessage;

import java.util.UUID;

public record   IzzyBotResponse(UUID id, ChatMessage message) {}
