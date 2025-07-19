package com.solesonic.izzybot.model.ollama;

import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.Map;

public class OllamaClients {
    private Map<String, ChatClient> chatClients;

    public void put(String clientName, ChatClient chatClient) {
        if(chatClients == null) {
            chatClients = new HashMap<>();
        }

        chatClients.put(clientName, chatClient);
    }

    public ChatClient get(String clientName) {
        return chatClients.get(clientName);
    }
}
