package com.solesonic.model.chat;

import java.util.Set;
import java.util.UUID;

public record ChatRequest (String chatMessage, Set<String> commands, Set<UUID> attachmentIds){
}
