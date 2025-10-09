package com.solesonic.config.olllama;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ChatConfig {
    private final SimpleLoggerAdvisor simpleLoggerAdvisor = new SimpleLoggerAdvisor();

    @Bean
    public ChatMemory chatMemory(DatabaseChatMemory databaseChatMemory) {
        return databaseChatMemory;
    }

    @Bean
    public ChatClient chatClient(ChatMemory chatMemory,
                                 OllamaChatModel chatModel,
                                 List<McpSyncClient> mcpClients) {
        SyncMcpToolCallbackProvider syncMcpToolCallbackProvider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpClients)
                .build();

        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(syncMcpToolCallbackProvider)
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
