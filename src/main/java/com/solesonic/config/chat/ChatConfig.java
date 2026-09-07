package com.solesonic.config.chat;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Collectors;

@Configuration
public class ChatConfig {
    public static final String DEFAULT_CHAT_CLIENT = "default_chat_client";

    private final SimpleLoggerAdvisor simpleLoggerAdvisor = SimpleLoggerAdvisor.builder()
            .requestToString(ChatConfig::requestWithToolDetails)
            .build();

    @Bean
    public ChatMemory chatMemory(DatabaseChatMemory databaseChatMemory) {
        return databaseChatMemory;
    }

    /**
     * Carries no default tools: {@code ChatClient} only ever adds request-level tools on top of a
     * builder's defaults, never replaces them, so a shared bean with every MCP tool baked in here
     * would make it impossible for any one call site to offer a narrower set. Every caller registers
     * exactly the tools it wants per request instead.
     */
    @Bean
    @Qualifier(DEFAULT_CHAT_CLIENT)
    public ChatClient defaultChatClient(ChatMemory chatMemory,
                                        ChatClient.Builder chatClientBuilder) {

        MessageChatMemoryAdvisor messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .build();

        return chatClientBuilder
                .defaultAdvisors(messageChatMemoryAdvisor, simpleLoggerAdvisor)
                .build();
    }

    /**
     * {@code OpenAiChatOptions} never overrides {@code toString()}, so {@link SimpleLoggerAdvisor}'s
     * default request formatter — which just calls {@code chatClientRequest.toString()} — logs the
     * options as an identity hash and drops the tool callbacks living inside it, even though those
     * are exactly what get sent to the model. This appends each tool's name, description and input
     * schema explicitly. There is no output schema to log: neither Spring AI's {@code ToolDefinition}
     * nor the OpenAI function-calling protocol it targets declares one — the model only ever gets
     * back whatever string the tool call returns.
     */
    private static String requestWithToolDetails(ChatClientRequest chatClientRequest) {
        if (chatClientRequest == null) {
            return "null";
        }

        String toolDetails = "none";

        if (chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
                && !CollectionUtils.isEmpty(toolCallingChatOptions.getToolCallbacks())) {
            toolDetails = toolCallingChatOptions.getToolCallbacks().stream()
                    .map(ToolCallback::getToolDefinition)
                    .map(toolDefinition -> "%n  - %s: %s%n    inputSchema=%s".formatted(
                            toolDefinition.name(), toolDefinition.description(), toolDefinition.inputSchema()))
                    .collect(Collectors.joining());
        }

        return chatClientRequest + ", tools=[" + toolDetails + "]";
    }
}
