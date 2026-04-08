package com.solesonic.config.olllama;

import com.solesonic.mcp.client.McpIdentityProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;

@Configuration
public class ChatConfig {
    public static final String DEFAULT_CHAT_CLIENT = "default_chat_client";
    public static final String TASK_CHAT_CLIENT = "task_chat_client";

    private final SimpleLoggerAdvisor simpleLoggerAdvisor = new SimpleLoggerAdvisor();
    private final McpIdentityProvider mcpToolCallbackProvider;

    public ChatConfig(McpIdentityProvider mcpToolCallbackProvider) {
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    @Bean
    public ChatMemory chatMemory(DatabaseChatMemory databaseChatMemory) {
        return databaseChatMemory;
    }

    @Bean
    @Qualifier(DEFAULT_CHAT_CLIENT)
    public ChatClient defaultChatClient(ChatMemory chatMemory,
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
    @Qualifier(TASK_CHAT_CLIENT)
    public ChatClient taskChatClient(ChatMemory chatMemory,
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
