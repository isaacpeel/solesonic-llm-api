package com.solesonic.service.prompt;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlashCommandServiceTest {

    @Mock
    private McpSyncClient mcpSyncClient;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private McpSchema.ListPromptsResult listPromptsResult;

    @Mock
    private McpSchema.Prompt jiraPrompt;

    @Mock
    private McpSchema.Prompt sportsPrompt;

    private SlashCommandService slashCommandService;

    @BeforeEach
    void setUp() {
        slashCommandService = new SlashCommandService(
                List.of(mcpSyncClient),
                redisTemplate,
                JsonMapper.builder().build(),
                300,
                true
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldReturnCachedSlashCommandsWhenCacheExists() {
        when(valueOperations.get(anyString())).thenReturn(Mono.just("[{\"command\":\"/jira\",\"name\":\"jira\",\"description\":\"jira prompt\"}]"));

        var slashCommands = slashCommandService.slashCommands();

        assertThat(slashCommands).hasSize(1);
        assertThat(slashCommands.getFirst().command()).isEqualTo("/jira");
        verify(mcpSyncClient, never()).listPrompts();
    }

    @Test
    void shouldLoadAndCacheSlashCommandsWhenCacheMisses() {
        when(valueOperations.get(anyString())).thenReturn(Mono.empty());
        when(mcpSyncClient.listPrompts()).thenReturn(listPromptsResult);
        when(listPromptsResult.prompts()).thenReturn(List.of(jiraPrompt, sportsPrompt));
        when(jiraPrompt.name()).thenReturn("jira");
        when(jiraPrompt.description()).thenReturn("jira prompt");
        when(sportsPrompt.name()).thenReturn("sports");
        when(sportsPrompt.description()).thenReturn("sports prompt");
        when(valueOperations.set(anyString(), anyString(), any())).thenReturn(Mono.just(Boolean.TRUE));

        var slashCommands = slashCommandService.slashCommands();

        assertThat(slashCommands).hasSize(2);
        assertThat(slashCommands.get(0).command()).isEqualTo("/jira");
        assertThat(slashCommands.get(1).command()).isEqualTo("/sports");
        verify(valueOperations).set(eq("slash:commands:catalog"), anyString(), any());
    }

    @Test
    void shouldWarmupOnStartupWhenEnabled() {
        when(valueOperations.get(anyString())).thenReturn(Mono.empty());
        when(mcpSyncClient.listPrompts()).thenReturn(listPromptsResult);
        when(listPromptsResult.prompts()).thenReturn(List.of(jiraPrompt));
        when(jiraPrompt.name()).thenReturn("jira");
        when(jiraPrompt.description()).thenReturn("jira prompt");
        when(valueOperations.set(anyString(), anyString(), any())).thenReturn(Mono.just(Boolean.TRUE));

        slashCommandService.warmupCatalogOnStartup();

        verify(mcpSyncClient).listPrompts();
    }
}
