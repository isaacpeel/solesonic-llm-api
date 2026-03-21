package com.solesonic.service.prompt;

import com.solesonic.exception.ChatException;
import com.solesonic.model.prompt.SlashCommandPrompt;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
public class SlashCommandService {
    private static final Logger log = LoggerFactory.getLogger(SlashCommandService.class);
    private static final String CACHE_KEY = "slash:commands:catalog";
    private static final TypeReference<List<SlashCommandPrompt>> CATALOG_TYPE_REFERENCE = new TypeReference<>() {
    };
    public static final String COMMAND = "command";

    private final List<McpSyncClient> mcpSyncClients;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final long cacheTtlSeconds;
    private final boolean warmupOnStartup;

    public SlashCommandService(List<McpSyncClient> mcpSyncClients,
                               ReactiveStringRedisTemplate redisTemplate,
                               JsonMapper jsonMapper,
                               @Value("${solesonic.llm.slash-commands.cache.ttl-seconds:3600}") long cacheTtlSeconds,
                               @Value("${solesonic.llm.slash-commands.cache.warmup-on-startup:true}") boolean warmupOnStartup) {
        this.mcpSyncClients = mcpSyncClients;
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.warmupOnStartup = warmupOnStartup;
    }

    public SlashCommandPrompt command(String command) {
        return slashCommands().stream()
                .filter(prompt -> prompt.command().equals(command))
                .findFirst()
                .orElseThrow(() -> new ChatException("No prompt found for command: " + command));
    }

    public List<SlashCommandPrompt> typeAhead(String commandPrefix) {
        log.info("Type ahead for command search: {}", commandPrefix);

        return slashCommands().stream()
                .filter(prompt -> {
                    String prefix = commandPrefix.toLowerCase();
                    return StringUtils.isEmpty(prefix) || prompt.command().startsWith(prefix);
                })
                .toList();
    }

    public List<SlashCommandPrompt> slashCommands() {
        String cachedPayload = redisTemplate.opsForValue().get(CACHE_KEY).block();

        if (StringUtils.isNotBlank(cachedPayload)) {
            return jsonMapper.readValue(cachedPayload, CATALOG_TYPE_REFERENCE);
        }

        return refreshSlashCommands();
    }

    public List<SlashCommandPrompt> refreshSlashCommands() {
        List<SlashCommandPrompt> slashCommands = loadSlashCommandsFromMcp();

        if (slashCommands.isEmpty()) {
            return slashCommands;
        }

        String serializedPayload = jsonMapper.writeValueAsString(slashCommands);

        redisTemplate.opsForValue()
                .set(CACHE_KEY, serializedPayload, Duration.ofSeconds(cacheTtlSeconds))
                .onErrorResume(exception -> {
                    log.warn("Failed to cache slash-command catalog in Redis: {}", exception.getMessage());

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

        List<SlashCommandPrompt> slashCommands = slashCommands();
        log.info("Slash-command prompt catalog ready with {} command(s)", slashCommands.size());
    }

    private List<SlashCommandPrompt> loadSlashCommandsFromMcp() {
        if (mcpSyncClients == null || mcpSyncClients.isEmpty()) {
            log.warn("No MCP sync clients configured; slash-command prompt catalog is empty");

            return Collections.emptyList();
        }

        McpSyncClient mcpSyncClient = mcpSyncClients.getFirst();
        McpSchema.ListPromptsResult listPromptsResult = mcpSyncClient.listPrompts();

        if(listPromptsResult == null) {
            return Collections.emptyList();
        }

        List<McpSchema.Prompt> mcpPrompts = listPromptsResult.prompts();

        return mcpPrompts.stream()
                .filter(listedPrompt -> StringUtils.isNotBlank(listedPrompt.name()))
                .filter(listPrompt -> listPrompt.meta() != null)
                .filter(listedPrompt -> listedPrompt.meta().get(COMMAND) != null)
                .map(prompt -> new SlashCommandPrompt(prompt.meta().get(COMMAND).toString(), prompt.name(), prompt.description()))
                .sorted(Comparator.comparing(SlashCommandPrompt::command))
                .toList();
    }
}
