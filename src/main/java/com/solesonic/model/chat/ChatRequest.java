package com.solesonic.model.chat;

import java.util.Set;

public record ChatRequest (String chatMessage, Set<String> commands){
}
