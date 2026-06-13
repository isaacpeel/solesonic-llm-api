package com.solesonic.service.prompt;

import com.solesonic.exception.ChatException;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlashCommandServiceTest {

//    private static final String CACHE_KEY = "slash:commands:catalog";
//
//    @Mock
//    private McpSyncClient mcpSyncClient;
//    @Mock
//    private ReactiveStringRedisTemplate redisTemplate;
//    @Mock
//    private JsonMapper jsonMapper;
//    @Mock
//    private ChatMemory chatMemory;
//    @Mock
//    private OllamaApi ollamaApi;
//    @Mock
//    private ReactiveValueOperations<String, String> valueOperations;
//
//    private SlashCommandService slashCommandService;
//
//    @BeforeEach
//    void setUp() {
//        slashCommandService = new SlashCommandService(
//                List.of(mcpSyncClient),
//                redisTemplate,
//                jsonMapper,
//                chatMemory,
//                ollamaApi,
//                Optional.empty(),
//                3600L,
//                false,
//                "llama3");
//    }
//
//    private void stubCacheHit(String payload) {
//        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
//        when(valueOperations.get(CACHE_KEY)).thenReturn(Mono.just(payload));
//    }
//
//    private PromptSlashCommand makePromptCommand(String command, String name) {
//        return new PromptSlashCommand(command, name, name + " description");
//    }
//
//    @Test
//    void commands_withMatchingCommandInCatalog_returnsMatchedCommands() {
//        PromptSlashCommand askCommand = makePromptCommand("/ask", "ask");
//        stubCacheHit("cached-json");
//        doReturn(List.of(askCommand)).when(jsonMapper)
//                .readValue(eq("cached-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());
//
//        List<SlashCommand> result = slashCommandService.commands(Set.of("/ask"));
//
//        assertThat(result).hasSize(1);
//        assertThat(result.getFirst().command()).isEqualTo("/ask");
//    }
//
//    @Test
//    void commands_withNoMatchingCommand_throwsChatException() {
//        PromptSlashCommand askCommand = makePromptCommand("/ask", "ask");
//        stubCacheHit("cached-json");
//        doReturn(List.of(askCommand)).when(jsonMapper)
//                .readValue(eq("cached-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());
//
//        assertThatThrownBy(() -> slashCommandService.commands(Set.of("/unknown")))
//                .isInstanceOf(ChatException.class)
//                .hasMessageContaining("/unknown");
//    }
//
//    @Test
//    void typeAhead_withEmptyInput_returnsAllCommands() {
//        PromptSlashCommand command1 = makePromptCommand("/ask", "ask");
//        PromptSlashCommand command2 = makePromptCommand("/weather", "weather");
//        PromptSlashCommand command3 = makePromptCommand("/search", "search");
//        stubCacheHit("cached-json");
//        doReturn(List.of(command1, command2, command3)).when(jsonMapper)
//                .readValue(eq("cached-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());
//
//        List<SlashCommand> result = slashCommandService.typeAhead("");
//
//        assertThat(result).hasSize(3);
//    }
//
//    @Test
//    void typeAhead_withMatchingSearchTerm_returnsFilteredCommands() {
//        PromptSlashCommand weatherCommand = makePromptCommand("/weather", "weather");
//        PromptSlashCommand askCommand = makePromptCommand("/ask", "ask");
//        stubCacheHit("cached-json");
//        doReturn(List.of(weatherCommand, askCommand)).when(jsonMapper)
//                .readValue(eq("cached-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());
//
//        List<SlashCommand> result = slashCommandService.typeAhead("weather");
//
//        assertThat(result).hasSize(1);
//        assertThat(result.getFirst().command()).isEqualTo("/weather");
//    }
//
//    @Test
//    void typeAhead_withNoMatchingTerm_returnsAllCommands() {
//        PromptSlashCommand command1 = makePromptCommand("/ask", "ask");
//        PromptSlashCommand command2 = makePromptCommand("/search", "search");
//        stubCacheHit("cached-json");
//        doReturn(List.of(command1, command2)).when(jsonMapper)
//                .readValue(eq("cached-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());
//
//        List<SlashCommand> result = slashCommandService.typeAhead("zzz");
//
//        assertThat(result).hasSize(2);
//    }
//
//    @Test
//    void slashCommands_withValidCachedPayload_deserializesFromRedis() {
//        PromptSlashCommand askCommand = makePromptCommand("/ask", "ask");
//        stubCacheHit("cached-json");
//        doReturn(List.of(askCommand)).when(jsonMapper)
//                .readValue(eq("cached-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());
//
//        List<SlashCommand> result = slashCommandService.slashCommands();
//
//        assertThat(result).hasSize(1);
//        verify(mcpSyncClient, never()).listPrompts();
//    }
//
//    @Test
//    void slashCommands_withStaleCachePayload_refreshesFromMcp() {
//        stubCacheHit("stale-json");
//
//        InvalidTypeIdException staleException = mock(InvalidTypeIdException.class);
//        doThrow(staleException).when(jsonMapper)
//                .readValue(eq("stale-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());
//
//        McpSchema.Prompt mcpPrompt = McpSchema.Prompt.builder("basic-prompt")
//                .description("Basic prompt")
//                .meta(Map.of(SlashCommand.COMMAND, "/basic-prompt"))
//                .build();
//        McpSchema.ListPromptsResult listPromptsResult =
//                new McpSchema.ListPromptsResult(List.of(mcpPrompt), null, null);
//        when(mcpSyncClient.listPrompts()).thenReturn(listPromptsResult);
//        when(mcpSyncClient.listTools()).thenThrow(new IllegalStateException("no tools capability"));
//
//        when(jsonMapper.writeValueAsString(any())).thenReturn("refreshed-json");
//        when(valueOperations.set(anyString(), anyString(), any(Duration.class)))
//                .thenReturn(Mono.just(true));
//
//        List<SlashCommand> result = slashCommandService.slashCommands();
//
//        assertThat(result).hasSize(1);
//        verify(mcpSyncClient).listPrompts();
//    }
}
