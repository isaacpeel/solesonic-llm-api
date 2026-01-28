package com.solesonic.service.intent;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Service
public class UserIntentService {
    private static final Logger log = LoggerFactory.getLogger(UserIntentService.class);
    public static final String USER_MESSAGE = "user_message";
    public static final String AGENT_NAME = "agent_name";
    public static final String PROMPT_CATALOG = "prompt_catalog";

    private final OllamaApi ollamaApi;
    private final List<McpSyncClient> mcpSyncClients;
    private final JsonMapper jsonMapper;

    @Value("classpath:prompts/intent_prompt.st")
    private Resource intentPrompt;

    @Value("${solesonic.llm.intent.model}")
    private String intentModel;

    @Value("${solesonic.llm.bot.name}")
    private String agentName;

    public UserIntentService(OllamaApi ollamaApi,
                             List<McpSyncClient> mcpSyncClients,
                             JsonMapper jsonMapper) {
        this.ollamaApi = ollamaApi;
        this.mcpSyncClients = mcpSyncClients;
        this.jsonMapper = jsonMapper;
    }

    public Prompt prompt(String userMessage) {
        McpSyncClient mcpSyncClient = mcpSyncClients.getFirst();
        McpSchema.ListPromptsResult listPromptsResults = mcpSyncClient.listPrompts();

        String promptList = jsonMapper.writeValueAsString(listPromptsResults);
        PromptTemplate promptTemplate = new PromptTemplate(intentPrompt);

        Map<String, Object> context = Map.of(
                AGENT_NAME, agentName,
                USER_MESSAGE, userMessage,
                PROMPT_CATALOG, promptList
        );

        return promptTemplate.create(context);
    }

    /**
     * Determines the user's intent based on their message using LLM classification.
     *
     * @param userMessage the user's input message
     * @return the classified prompt
     */
    public String determineIntent(String userMessage) {
        log.info("Determining intent for message: {}", userMessage);

        McpSyncClient mcpSyncClient = mcpSyncClients.getFirst();
        PromptTemplate promptTemplate = new PromptTemplate(intentPrompt);

        OllamaChatOptions ollamaOptions = OllamaChatOptions.builder()
                .model(intentModel)
                .temperature(0.7)
                .build();

        ModelManagementOptions modelManagementOptions = ModelManagementOptions.builder()
                .pullModelStrategy(PullModelStrategy.WHEN_MISSING)
                .build();

        OllamaChatModel intentChatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(ollamaOptions)
                .modelManagementOptions(modelManagementOptions)
                .build();

        ChatClient intentChatClient = ChatClient.builder(intentChatModel)
                .build();

        McpSchema.ListPromptsResult listPromptsResults = mcpSyncClient.listPrompts();

        Map<String, Object> context;
        String promptList = jsonMapper.writeValueAsString(listPromptsResults);

        context = Map.of(
                USER_MESSAGE, userMessage,
                PROMPT_CATALOG, promptList
        );

        Prompt intentPrompt = promptTemplate.create(context);

        ChatClient.CallResponseSpec call = intentChatClient.prompt(intentPrompt).call();
        return call.content();
    }
}
