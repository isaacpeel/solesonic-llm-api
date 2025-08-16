package com.solesonic.config.olllama;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    private final SimpleLoggerAdvisor simpleLoggerAdvisor = new SimpleLoggerAdvisor();

    @Bean
    public ChatMemory chatMemory(DatabaseChatMemory databaseChatMemory) {
        return databaseChatMemory;
    }

    @Bean
    public ChatClient chatClient(ChatMemory chatMemory, OllamaChatModel chatModel) {

        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(chatMemory).build(),
                        simpleLoggerAdvisor
                )
                .build();
    }
}
