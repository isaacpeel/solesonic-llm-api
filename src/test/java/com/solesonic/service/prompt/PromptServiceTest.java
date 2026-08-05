package com.solesonic.service.prompt;

import com.solesonic.mcp.client.prompt.McpPromptAdapter;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.service.vision.ImageDescriptionService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.solesonic.service.prompt.PromptService.BASIC_PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private UserPreferencesService userPreferencesService;
    @Mock
    private SlashCommandService slashCommandService;
    @Mock
    private McpSyncClient mcpClient;
    @Mock
    private McpPromptAdapter mcpPromptAdapter;
    @Mock
    private ToolCallService toolCallService;
    @Mock
    private A2AAgentService a2aAgentService;
    @Mock
    private A2AStickyAgentService a2aStickyAgentService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private ImageDescriptionService imageDescriptionService;
    @Mock
    private Authentication authentication;
    @Mock
    private Jwt jwt;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamResponseSpec;

    private UUID chatId;
    private UUID userId;

    private PromptService promptService;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        promptService = new PromptService(
                chatClient,
                userPreferencesService,
                slashCommandService,
                mcpClient,
                mcpPromptAdapter,
                toolCallService,
                a2aAgentService,
                a2aStickyAgentService,
                vectorStoreService,
                imageDescriptionService);

        ReflectionTestUtils.setField(promptService, "agentName", "Izzy");

        lenient().when(authentication.getPrincipal()).thenReturn(jwt);
        lenient().when(jwt.getTokenValue()).thenReturn("token-abc");

        //No attachments in most tests: nothing to describe.
        lenient().when(imageDescriptionService.describe(any(), any(), any())).thenReturn(List.of());
        lenient().when(userPreferencesService.get(userId)).thenReturn(preferencesWithModel("llama3"));
        lenient().when(vectorStoreService.retrievalAugmentationAdvisor(any(UUID.class)))
                .thenReturn(mock(Advisor.class));
    }

    private UserPreferences preferencesWithModel(String model) {
        UserPreferences preferences = new UserPreferences();
        preferences.setModel(model);
        return preferences;
    }

    private void stubBasicPromptChain(Flux<String> emissions) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.messages(ArgumentMatchers.<Message>any())).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(ArgumentMatchers.<Advisor>any())).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(emissions);
    }

    private void stubPromptChainWithPrompt(Flux<String> emissions) {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(ArgumentMatchers.<Advisor>any())).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(emissions);
    }

    private McpSchema.GetPromptResult basicPromptResult() {
        McpSchema.TextContent textContent = new McpSchema.TextContent(null, "You are Izzy", null);
        McpSchema.PromptMessage promptMessage =
                new McpSchema.PromptMessage(McpSchema.Role.USER, textContent);
        return new McpSchema.GetPromptResult(null, List.of(promptMessage), null);
    }

    @Test
    void stream_withNonJwtPrincipal_throwsIllegalStateException() {
        when(authentication.getPrincipal()).thenReturn("not-a-jwt");
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of());

        assertThatThrownBy(() -> promptService.stream(chatId, userId, chatRequest, authentication).blockFirst())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stream_withNoCommandsAndStickyAgentPresent_delegatesToA2AAgent() {
        ChatRequest chatRequest = new ChatRequest("what is the weather?", Set.of(), Set.of());
        when(a2aStickyAgentService.getActiveAgent(chatId))
                .thenReturn(Mono.just(Optional.of("weather-agent")));
        when(a2aAgentService.delegate(eq(chatId), eq("weather-agent"), anyString(), anyString()))
                .thenReturn(Flux.just("forecast"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("forecast")
                .verifyComplete();

        verify(a2aAgentService).delegate(eq(chatId), eq("weather-agent"), anyString(), anyString());
        verify(mcpClient, never()).getPrompt(any());
    }

    @Test
    void stream_withNoCommandsAndNoStickyAgent_routesToBasicPrompt() {
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of());
        when(a2aStickyAgentService.getActiveAgent(chatId))
                .thenReturn(Mono.just(Optional.empty()));
        McpSchema.GetPromptResult getPromptResult = basicPromptResult();
        when(mcpClient.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(getPromptResult);
        when(mcpPromptAdapter.toSystemText(getPromptResult)).thenReturn("You are Izzy");
        stubBasicPromptChain(Flux.just("hello"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("hello")
                .verifyComplete();

        verify(mcpClient).getPrompt(argThat(request -> BASIC_PROMPT.equals(request.name())));
    }

    @Test
    void stream_withPromptSlashCommand_fetchesMcpPromptAndStreams() {
        PromptSlashCommand promptCommand = new PromptSlashCommand("/ask", "ask", "Ask a question");
        ChatRequest chatRequest = new ChatRequest("tell me something", Set.of("/ask"), Set.of());
        when(slashCommandService.commands(Set.of("/ask"))).thenReturn(List.of(promptCommand));

        McpSchema.TextContent userContent = new McpSchema.TextContent(null, "tell me something", null);
        McpSchema.PromptMessage userMessage = new McpSchema.PromptMessage(McpSchema.Role.USER, userContent);
        McpSchema.GetPromptResult getPromptResult =
                new McpSchema.GetPromptResult(null, List.of(userMessage), null);
        when(mcpClient.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(getPromptResult);
        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        stubPromptChainWithPrompt(Flux.just("answer"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("answer")
                .verifyComplete();

        verify(a2aStickyAgentService).deactivate(chatId);
    }

    @Test
    void stream_withToolSlashCommand_delegatesToToolCallService() {
        McpSchema.Tool mcpTool = mock(McpSchema.Tool.class);
        when(mcpTool.name()).thenReturn("search");
        when(mcpTool.description()).thenReturn("Search tool");
        ToolSlashCommand toolCommand = new ToolSlashCommand(mcpTool);
        ChatRequest chatRequest = new ChatRequest("search for cats", Set.of("search"), Set.of());

        when(slashCommandService.commands(Set.of("search"))).thenReturn(List.of(toolCommand));
        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        when(toolCallService.stream(eq(chatId), anyString(), eq(toolCommand), any()))
                .thenReturn(Flux.just("tool-result"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("tool-result")
                .verifyComplete();

        verify(toolCallService).stream(eq(chatId), anyString(), eq(toolCommand), any());
    }

    @Test
    void stream_withAgentSlashCommand_activatesStickyAndDelegates() {
        AgentSlashCommand agentCommand = new AgentSlashCommand("weather-agent", "weather-agent", "Weather");
        ChatRequest chatRequest = new ChatRequest("what is the weather?", Set.of("weather-agent"), Set.of());
        when(slashCommandService.commands(Set.of("weather-agent"))).thenReturn(List.of(agentCommand));
        when(a2aStickyAgentService.activate(chatId, "weather-agent")).thenReturn(Mono.empty());
        when(a2aAgentService.delegate(eq(chatId), eq("weather-agent"), anyString(), anyString()))
                .thenReturn(Flux.just("a2a-result"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("a2a-result")
                .verifyComplete();

        verify(a2aStickyAgentService).activate(chatId, "weather-agent");
    }

    /**
     * The image block travels as its own message, and the user message keeps the user's own words.
     * Folding the block into the user message is what let the retrieval advisor bury it: that
     * advisor rewrites the last user message into "here is retrieved context, answer from it and no
     * prior knowledge", and an image description inside that wrapper loses to the documents.
     */
    @Test
    void stream_withAttachments_sendsTheImageContextAsItsOwnMessage() {
        UUID attachmentId = UUID.randomUUID();
        ChatRequest chatRequest = new ChatRequest("what is this?", Set.of(), Set.of(attachmentId));

        when(imageDescriptionService.describe(chatId, userId, Set.of(attachmentId)))
                .thenReturn(List.of(new ChatAttachmentDescription(
                        UUID.randomUUID(), "screenshot.png", null, "a login screen")));
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        McpSchema.GetPromptResult getPromptResult = basicPromptResult();
        when(mcpClient.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(getPromptResult);
        when(mcpPromptAdapter.toSystemText(getPromptResult)).thenReturn("You are Izzy");
        stubBasicPromptChain(Flux.just("that is a login screen"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("that is a login screen")
                .verifyComplete();

        verify(requestSpec).user("what is this?");

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(requestSpec).messages(messageCaptor.capture());

        Message imageContextMessage = messageCaptor.getValue();

        assertThat(imageContextMessage.getText())
                .contains("screenshot.png")
                .contains("a login screen");

        //A user message, not a system one: MessageChatMemoryAdvisor hoists system messages to the
        //front of the prompt, which would strand this behind the whole conversation history.
        assertThat(imageContextMessage).isInstanceOf(UserMessage.class);

        // The MCP prompt template renders from the user's own words, not the description block.
        verify(mcpClient).getPrompt(argThat(request ->
                "what is this?".equals(request.arguments().get(PromptService.USER_MESSAGE))));
    }

    @Test
    void stream_withoutAttachments_addsNoImageContextMessage() {
        ChatRequest chatRequest = new ChatRequest("plain question", Set.of(), Set.of());

        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        McpSchema.GetPromptResult getPromptResult = basicPromptResult();
        when(mcpClient.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(getPromptResult);
        when(mcpPromptAdapter.toSystemText(getPromptResult)).thenReturn("You are Izzy");
        stubBasicPromptChain(Flux.just("an answer"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("an answer")
                .verifyComplete();

        verify(requestSpec).user("plain question");
        verify(requestSpec, never()).messages(ArgumentMatchers.<Message>any());
    }

    @Test
    void stream_withToolSlashCommandAndAttachments_sendsTheOriginalMessage() {
        McpSchema.Tool mcpTool = mock(McpSchema.Tool.class);
        when(mcpTool.name()).thenReturn("search");
        when(mcpTool.description()).thenReturn("Search tool");
        ToolSlashCommand toolCommand = new ToolSlashCommand(mcpTool);
        UUID attachmentId = UUID.randomUUID();
        ChatRequest chatRequest = new ChatRequest("search for cats", Set.of("search"), Set.of(attachmentId));

        when(imageDescriptionService.describe(chatId, userId, Set.of(attachmentId)))
                .thenReturn(List.of(new ChatAttachmentDescription(
                        UUID.randomUUID(), "cat.png", null, "a cat")));
        when(slashCommandService.commands(Set.of("search"))).thenReturn(List.of(toolCommand));
        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        when(toolCallService.stream(eq(chatId), anyString(), eq(toolCommand), any()))
                .thenReturn(Flux.just("tool-result"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("tool-result")
                .verifyComplete();

        // Image descriptions must not reach a tool's arguments.
        verify(toolCallService).stream(eq(chatId), eq("search for cats"), eq(toolCommand), any());
    }

    @Test
    void model_returnsModelFromUserPreferences() {
        when(userPreferencesService.get(userId)).thenReturn(preferencesWithModel("mistral"));

        String model = promptService.model(userId);

        assertThat(model).isEqualTo("mistral");
    }
}
