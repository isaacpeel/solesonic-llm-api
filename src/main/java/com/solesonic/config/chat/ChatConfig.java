package com.solesonic.config.chat;

import com.solesonic.mcp.client.McpIdentityProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    public static final String DEFAULT_CHAT_CLIENT = "default_chat_client";

    private final SimpleLoggerAdvisor simpleLoggerAdvisor = new SimpleLoggerAdvisor();

    @Bean
    public ChatMemory chatMemory(DatabaseChatMemory databaseChatMemory) {
        return databaseChatMemory;
    }

    @Bean
    @Qualifier(DEFAULT_CHAT_CLIENT)
    public ChatClient defaultChatClient(ChatMemory chatMemory,
                                        McpIdentityProvider mcpToolCallbackProvider,
                                        ChatClient.Builder chatClientBuilder) {

        MessageChatMemoryAdvisor messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .build();

        return chatClientBuilder
                .defaultTools(mcpToolCallbackProvider)
                .defaultAdvisors(messageChatMemoryAdvisor, simpleLoggerAdvisor)
                .build();
    }
}
