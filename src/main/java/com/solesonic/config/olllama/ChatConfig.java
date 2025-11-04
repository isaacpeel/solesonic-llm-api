package com.solesonic.config.olllama;

import com.solesonic.mcp.client.SecurityContextPropagatingMcpToolCallbackProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    private final SimpleLoggerAdvisor simpleLoggerAdvisor = new SimpleLoggerAdvisor();
    private final SecurityContextPropagatingMcpToolCallbackProvider mcpToolCallbackProvider;

    public ChatConfig(SecurityContextPropagatingMcpToolCallbackProvider mcpToolCallbackProvider) {
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    @Bean
    public ChatMemory chatMemory(DatabaseChatMemory databaseChatMemory) {
        return databaseChatMemory;
    }

    @Bean
    public ChatClient chatClient(ChatMemory chatMemory,
                                 OllamaChatModel chatModel) {

        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(mcpToolCallbackProvider)
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(chatMemory).build(),
                        simpleLoggerAdvisor
                )
                .build();
    }

    @Bean
    public OllamaApi ollamaApi(@Value("${spring.ai.ollama.base-url}") String ollamaBaseUrl) {
        return OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
    }
}
