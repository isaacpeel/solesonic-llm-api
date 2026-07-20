package com.solesonic.service.prompt;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.exception.ChatException;
import com.solesonic.mcp.client.IdentityToolCallback;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.tools.LocalToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;

@Service
public class SlashCommandService {
    private static final Logger log = LoggerFactory.getLogger(SlashCommandService.class);
    private static final String CACHE_KEY = "slash:commands:catalog";

    private static final TypeReference<List<SlashCommand>> CATALOG_TYPE_REFERENCE = new TypeReference<>() {
    };

    private final McpSyncClient mcpSyncClient;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final ChatMemory chatMemory;
    private final long cacheTtlSeconds;
    private final boolean warmupOnStartup;
    private final Optional<A2AAgentRegistry> a2aAgentRegistry;
    private final LocalToolRegistry localToolRegistry;

    private final SimpleLoggerAdvisor simpleLoggerAdvisor = new SimpleLoggerAdvisor();
    private final OllamaChatModel taskChatModel;

    public SlashCommandService(List<McpSyncClient> mcpSyncClients,
                               ReactiveStringRedisTemplate redisTemplate,
                               JsonMapper jsonMapper,
                               ChatMemory chatMemory,
                               OllamaApi ollamaApi,
                               Optional<A2AAgentRegistry> a2aAgentRegistry,
                               LocalToolRegistry localToolRegistry,
                               @Value("${solesonic.llm.slash-commands.cache.ttl-seconds:3600}") long cacheTtlSeconds,
                               @Value("${solesonic.llm.slash-commands.cache.warmup-on-startup:true}") boolean warmupOnStartup,
                               @Value("${solesonic.llm.tool-call.model:qwen2.5:7b}") String taskModel) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.chatMemory = chatMemory;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.warmupOnStartup = warmupOnStartup;
        this.a2aAgentRegistry = a2aAgentRegistry;
        this.localToolRegistry = localToolRegistry;

        mcpSyncClient = mcpSyncClients.getFirst();

        OllamaChatOptions taskChatOptions = OllamaChatOptions.builder()
                .model(taskModel)
                .build();

        taskChatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(taskChatOptions)
                .build();
    }

    public ChatClient taskClient(ToolCallback toolCallback) {
        log.info("Creating task client with tool: {}", toolCallback.getToolDefinition().name());

        IdentityToolCallback identityToolCallback = new IdentityToolCallback(toolCallback);

        return ChatClient.builder(taskChatModel)
                .defaultTools(identityToolCallback)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        simpleLoggerAdvisor
                )
                .build();
    }

    public List<SlashCommand> commands(Set<String> commands) {

        var matched = slashCommands().stream()
                .filter(slashCommand -> commands.contains(slashCommand.command()))
                .toList();

        if (matched.isEmpty()) {
            throw new ChatException("No commands found for commands: " + commands);
        }

        return matched;
    }

    public List<SlashCommand> typeAhead(String searchInput) {
        log.info("Type ahead for commands search: {}", searchInput);

        List<SlashCommand> allCommands = slashCommands();

        if (StringUtils.isEmpty(searchInput)) {
            return allCommands;
        }

        String searchTerm = searchInput.toLowerCase();

        List<SlashCommand> matches = allCommands.stream()
                .filter(slashCommand -> {
                    String command = slashCommand.command().toLowerCase();
                    String description = StringUtils.defaultString(slashCommand.description()).toLowerCase();
                    return command.contains(searchTerm) || description.contains(searchTerm);
                })
                .toList();

        if (matches.isEmpty()) {
            return allCommands;
        }

        return matches;
    }

    public List<SlashCommand> slashCommands() {
        String cachedPayload = redisTemplate.opsForValue()
                .get(CACHE_KEY)
                .block();

        if (StringUtils.isNotBlank(cachedPayload)) {
            try {
                return jsonMapper.readValue(cachedPayload, CATALOG_TYPE_REFERENCE);
            } catch (InvalidTypeIdException invalidTypeIdException) {
                log.warn("Cached slash-commands schema is stale, refreshing: {}", invalidTypeIdException.getMessage());
            }
        }

        return refreshSlashCommands();
    }

    public List<SlashCommand> refreshSlashCommands() {
        List<SlashCommand> slashCommands = loadSlashCommandsFromMcp();

        if (slashCommands.isEmpty()) {
            return slashCommands;
        }

        slashCommands
                .forEach(slashCommand -> log.debug("Loaded command: {}", slashCommand.name()));

        String serializedPayload = jsonMapper.writerFor(CATALOG_TYPE_REFERENCE).writeValueAsString(slashCommands);

        redisTemplate.opsForValue()
                .set(CACHE_KEY, serializedPayload, Duration.ofSeconds(cacheTtlSeconds))
                .onErrorResume(exception -> {
                    log.warn("Failed to cache slash-commands catalog in Redis: {}", exception.getMessage());

                    return Mono.just(Boolean.FALSE);
                })
                .block();

        return slashCommands;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupCatalogOnStartup() {
        if (!warmupOnStartup) {
            return;
        }

        redisTemplate.delete(CACHE_KEY)
                .doOnSuccess(_ -> log.info("Purged slash-commands cache on startup"))
                .onErrorResume(exception -> {
                    log.warn("Failed to purge slash-commands cache on startup: {}", exception.getMessage());
                    return Mono.just(0L);
                })
                .block();

        List<SlashCommand> slashCommands = slashCommands();
        log.info("Slash-commands prompt catalog ready with {} commands(s)", slashCommands.size());
    }

    private List<SlashCommand> loadSlashCommandsFromMcp() {
        McpSchema.ListPromptsResult listPromptsResult = mcpSyncClient.listPrompts();

        List<SlashCommand> promptCommands = List.of();

        if (listPromptsResult != null) {
            List<McpSchema.Prompt> mcpPrompts = listPromptsResult.prompts();

            promptCommands = mcpPrompts.stream()
                    .filter(listedPrompt -> StringUtils.isNotBlank(listedPrompt.name()))
                    .filter(listPrompt -> listPrompt.meta() != null)
                    .filter(listedPrompt -> listedPrompt.meta().get(SlashCommand.COMMAND) != null)
                    .<SlashCommand>map(PromptSlashCommand::new)
                    .sorted(Comparator.comparing(SlashCommand::command))
                    .toList();
        }

        List<SlashCommand> toolCommands = List.of();

        try {
            McpSchema.ListToolsResult listToolsResult = mcpSyncClient.listTools();

            toolCommands = listToolsResult.tools().stream()
                    .filter(tool -> StringUtils.isNotBlank(tool.name()))
                    .<SlashCommand>map(ToolSlashCommand::new)
                    .sorted(Comparator.comparing(SlashCommand::command))
                    .toList();
        } catch (IllegalStateException exception) {
            log.warn("MCP server does not support tools capability; skipping tool commands: {}",
                    exception.getMessage());
        }

        List<SlashCommand> agentCommands = a2aAgentRegistry
                .map(A2AAgentRegistry::asSlashCommands)
                .orElse(List.of());

        List<SlashCommand> localToolCommands = localToolRegistry.asSlashCommands();

        return ListUtils.union(
                ListUtils.union(ListUtils.union(promptCommands, toolCommands), agentCommands),
                localToolCommands);
    }
}
