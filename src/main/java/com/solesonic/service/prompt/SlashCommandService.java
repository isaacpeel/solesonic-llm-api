package com.solesonic.service.prompt;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.exception.ChatException;
import com.solesonic.model.prompt.SlashCommand;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;

import static com.solesonic.model.prompt.SlashCommand.*;

@Service
public class SlashCommandService {
    private static final Logger log = LoggerFactory.getLogger(SlashCommandService.class);
    private static final String CACHE_KEY = "slash:commands:catalog";
    private static final TypeReference<List<SlashCommand>> CATALOG_TYPE_REFERENCE = new TypeReference<>() {
    };

    private final McpSyncClient mcpSyncClient;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final long cacheTtlSeconds;
    private final boolean warmupOnStartup;
    private final Optional<A2AAgentRegistry> a2aAgentRegistry;

    public SlashCommandService(List<McpSyncClient> mcpSyncClients,
                               ReactiveStringRedisTemplate redisTemplate,
                               JsonMapper jsonMapper,
                               Optional<A2AAgentRegistry> a2aAgentRegistry,
                               @Value("${solesonic.llm.slash-commands.cache.ttl-seconds:3600}") long cacheTtlSeconds,
                               @Value("${solesonic.llm.slash-commands.cache.warmup-on-startup:true}") boolean warmupOnStartup) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.warmupOnStartup = warmupOnStartup;
        this.a2aAgentRegistry = a2aAgentRegistry;

        mcpSyncClient = mcpSyncClients.getFirst();
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

    public List<SlashCommand> typeAhead(String commandPrefix) {
        log.info("Type ahead for commands search: {}", commandPrefix);

        return slashCommands().stream()
                .filter(slashCommand -> {
                    String prefix = commandPrefix.toLowerCase();
                    return StringUtils.isEmpty(prefix) || slashCommand.command().toLowerCase().startsWith(prefix.toLowerCase());
                })
                .toList();
    }

    public List<SlashCommand> slashCommands() {
        String cachedPayload = redisTemplate.opsForValue()
                .get(CACHE_KEY)
                .block();

        if (StringUtils.isNotBlank(cachedPayload)) {
            return jsonMapper.readValue(cachedPayload, CATALOG_TYPE_REFERENCE);
        }

        return refreshSlashCommands();
    }

    public List<SlashCommand> refreshSlashCommands() {
        List<SlashCommand> slashCommands = loadSlashCommandsFromMcp();

        if (slashCommands.isEmpty()) {
            return slashCommands;
        }

        slashCommands
                .forEach(slashCommand -> log.info("Loaded command: {}", slashCommand.name()));

        String serializedPayload = jsonMapper.writeValueAsString(slashCommands);

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
                    .filter(listedPrompt -> listedPrompt.meta().get(COMMAND) != null)
                    .map(SlashCommand::new)
                    .sorted(Comparator.comparing(SlashCommand::command))
                    .toList();
        }

        List<SlashCommand> agentCommands = a2aAgentRegistry
                .map(A2AAgentRegistry::asSlashCommands)
                .orElse(List.of());

        return ListUtils.union(promptCommands, agentCommands);
    }
}
