package com.solesonic.service.prompt;

import com.solesonic.exception.ChatException;
import com.solesonic.model.prompt.ModelSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.service.image.GeneratedImageToolInterceptor;
import com.solesonic.service.image.ReferenceImageInjector;
import com.solesonic.tools.LocalToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectWriter;
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

    private static final String CACHE_KEY = "slash:commands:catalog";

    @Mock
    private McpSyncClient mcpSyncClient;
    @Mock
    private ReactiveStringRedisTemplate redisTemplate;
    @Mock
    private ReactiveValueOperations<String, String> valueOperations;
    @Mock
    private JsonMapper jsonMapper;
    @Mock
    private ObjectWriter objectWriter;
    @Mock
    private ChatMemory chatMemory;
    @Mock
    private OpenAiChatModel taskChatModel;
    @Mock
    private LocalToolRegistry localToolRegistry;
    @Mock
    private JwtDecoder jwtDecoder;
    @Mock
    private JwtAuthenticationConverter jwtAuthenticationConverter;
    @Mock
    private GeneratedImageToolInterceptor generatedImageToolInterceptor;
    @Mock
    private ReferenceImageInjector referenceImageInjector;

    private SlashCommandService slashCommandService;

    @BeforeEach
    void setUp() {
        slashCommandService = new SlashCommandService(
                List.of(mcpSyncClient),
                redisTemplate,
                jsonMapper,
                chatMemory,
                taskChatModel,
                Optional.empty(),
                localToolRegistry,
                jwtDecoder,
                jwtAuthenticationConverter,
                generatedImageToolInterceptor,
                referenceImageInjector,
                3600L,
                false);
    }

    private void stubCacheHit(String payload) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(Mono.just(payload));
    }

    /**
     * The catalog in Redis may have been written before {@code /model} existed, so the built-in
     * command is added on every read rather than stored with the MCP-sourced ones.
     */
    private void stubCachedCatalog(List<SlashCommand> cachedCommands) {
        stubCacheHit("cached-json");
        doReturn(cachedCommands).when(jsonMapper)
                .readValue(eq("cached-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());
    }

    private static PromptSlashCommand promptCommand(String command) {
        return new PromptSlashCommand(command, command, command + " description");
    }

    @Test
    void commandsReturnsTheMatchingCommand() {
        stubCachedCatalog(List.of(promptCommand("ask")));

        List<SlashCommand> matched = slashCommandService.commands(Set.of("ask"));

        assertThat(matched).extracting(SlashCommand::command).containsExactly("ask");
    }

    @Test
    void commandsThrowsWhenNothingMatches() {
        stubCachedCatalog(List.of(promptCommand("ask")));

        assertThatThrownBy(() -> slashCommandService.commands(Set.of("unknown")))
                .isInstanceOf(ChatException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void slashCommandsIncludesTheModelCommandAlongsideACatalogCachedWithoutIt() {
        stubCachedCatalog(List.of(promptCommand("ask")));

        List<SlashCommand> slashCommands = slashCommandService.slashCommands();

        assertThat(slashCommands).extracting(SlashCommand::command).containsExactly("model", "ask");
        assertThat(slashCommands.getFirst()).isInstanceOf(ModelSlashCommand.class);
    }

    /**
     * The catalog entry is all a client has to go on: it names where the picker's options come from.
     */
    @Test
    void theModelCommandCarriesItsArgumentDescriptor() {
        stubCachedCatalog(List.of(promptCommand("ask")));

        SlashCommand modelCommand = slashCommandService.typeAhead("model").getFirst();

        assertThat(modelCommand).isInstanceOfSatisfying(ModelSlashCommand.class, model -> {
            assertThat(model.argument().optionsPath()).isEqualTo("/chats/models");
            assertThat(model.argument().required()).isTrue();
        });
    }

    /**
     * {@code /model} is a setting command and never routes — nor may an MCP prompt sharing its name
     * slip in and route in its place.
     */
    @Test
    void commandsNeverResolvesTheModelCommand() {
        stubCachedCatalog(List.of(new PromptSlashCommand("model", "model", "An MCP prompt named model")));

        assertThatThrownBy(() -> slashCommandService.commands(Set.of(SlashCommand.MODEL)))
                .isInstanceOf(ChatException.class);
    }

    @Test
    void typeAheadWithNoInputReturnsEveryCommand() {
        stubCachedCatalog(List.of(promptCommand("ask"), promptCommand("weather"), promptCommand("search")));

        List<SlashCommand> matches = slashCommandService.typeAhead("");

        assertThat(matches).extracting(SlashCommand::command)
                .containsExactly("model", "ask", "weather", "search");
    }

    @Test
    void typeAheadFiltersByTheSearchTerm() {
        stubCachedCatalog(List.of(promptCommand("weather"), promptCommand("ask")));

        List<SlashCommand> matches = slashCommandService.typeAhead("weather");

        assertThat(matches).extracting(SlashCommand::command).containsExactly("weather");
    }

    @Test
    void typeAheadFindsTheModelCommand() {
        stubCachedCatalog(List.of(promptCommand("ask")));

        List<SlashCommand> matches = slashCommandService.typeAhead("mod");

        assertThat(matches).extracting(SlashCommand::command).containsExactly("model");
    }

    @Test
    void typeAheadWithNoMatchReturnsEveryCommand() {
        stubCachedCatalog(List.of(promptCommand("ask"), promptCommand("search")));

        List<SlashCommand> matches = slashCommandService.typeAhead("zzz");

        assertThat(matches).extracting(SlashCommand::command).containsExactly("model", "ask", "search");
    }

    @Test
    void slashCommandsServesACachedCatalogWithoutAskingTheMcpServer() {
        stubCachedCatalog(List.of(promptCommand("ask")));

        slashCommandService.slashCommands();

        verify(mcpSyncClient, never()).listPrompts();
    }

    /**
     * A catalog cached under an older schema cannot be read back, and is reloaded from the MCP
     * server rather than failing every command lookup until it expires.
     */
    @Test
    void slashCommandsRefreshesAStaleCachedCatalogFromTheMcpServer() {
        stubCacheHit("stale-json");
        doThrow(mock(InvalidTypeIdException.class)).when(jsonMapper)
                .readValue(eq("stale-json"), ArgumentMatchers.<TypeReference<List<SlashCommand>>>any());

        McpSchema.Prompt mcpPrompt = McpSchema.Prompt.builder("basic-prompt")
                .description("Basic prompt")
                .meta(Map.of(SlashCommand.COMMAND, "basic-prompt"))
                .build();

        when(mcpSyncClient.listPrompts()).thenReturn(new McpSchema.ListPromptsResult(List.of(mcpPrompt), null, null));
        when(mcpSyncClient.listTools()).thenThrow(new IllegalStateException("no tools capability"));
        when(jsonMapper.writerFor(ArgumentMatchers.<TypeReference<?>>any())).thenReturn(objectWriter);
        when(objectWriter.writeValueAsString(any())).thenReturn("refreshed-json");
        when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        List<SlashCommand> slashCommands = slashCommandService.slashCommands();

        assertThat(slashCommands).extracting(SlashCommand::command).containsExactly("model", "basic-prompt");
        verify(valueOperations).set(CACHE_KEY, "refreshed-json", Duration.ofSeconds(3600));
    }
}
