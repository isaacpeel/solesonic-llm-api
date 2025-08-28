package com.solesonic.config.olllama;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.ollama.OllamaChatModel;
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
    public ChatClient chatClient(ChatMemory chatMemory, OllamaChatModel chatModel, List<McpSyncClient> mcpClients) {
        SyncMcpToolCallbackProvider syncMcpToolCallbackProvider = new SyncMcpToolCallbackProvider(mcpClients);

        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(syncMcpToolCallbackProvider)
                .defaultAdvisors(
                        PromptChatMemoryAdvisor.builder(chatMemory).build(),
                        simpleLoggerAdvisor
                )
                .build();
    }
}
