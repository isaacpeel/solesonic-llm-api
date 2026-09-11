package com.solesonic.service.prompt;

import com.solesonic.mcp.client.McpIdentityProvider;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.prompt.AgentSlashCommand;
import com.solesonic.model.prompt.LocalToolSlashCommand;
import com.solesonic.model.prompt.PromptSlashCommand;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.litellm.LiteLlmHeaderRegistry;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.rag.VectorStoreService;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlashCommandRouterTest {

    private static final String SERVER_REPORTED_MODEL = "qwen3-8b-instruct-q6";

    @Mock
    private ChatClient chatClient;
    @Mock
    private McpSyncClient mcpClient;
    @Mock
    private McpIdentityProvider mcpIdentityProvider;
    @Mock
    private ToolCallService toolCallService;
    @Mock
    private A2AAgentService a2aAgentService;
    @Mock
    private A2AStickyAgentService a2aStickyAgentService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamResponseSpec;

    private UUID chatId;
    private UUID userId;

    private SlashCommandRouter slashCommandRouter;

    private static final AttachmentResolution NO_ATTACHMENTS =
            new AttachmentResolution(List.of(), null);

    private static final Map<String, Object> CONTEXT_MAP = Map.of("userToken", "token-abc");

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        slashCommandRouter = new SlashCommandRouter(
                chatClient,
                mcpClient,
                mcpIdentityProvider,
                toolCallService,
                a2aAgentService,
                a2aStickyAgentService,
                vectorStoreService,
                chatMessageService,
                new LiteLlmHeaderRegistry(Clock.systemUTC()),
                "Izzy",
                "qwen3-8b",
                Duration.ofMinutes(30));

        lenient().when(vectorStoreService.retrievalAugmentationAdvisor(any(UUID.class), any(UUID.class)))
                .thenReturn(mock(Advisor.class));
    }

    private void stubPromptChain(Flux<String> emissions) {
        stubPromptResponses(
                emissions.map(text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text))))));
    }

    private void stubPromptResponses(Flux<ChatResponse> chatResponses) {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        //A single-element array keeps this a one-argument varargs call on both sides of the stub,
        //since ToolCallback[] is spread directly into Object... rather than wrapped as one element.
        when(mcpIdentityProvider.getToolCallbacks()).thenReturn(new ToolCallback[] { mock(ToolCallback.class) });
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(ArgumentMatchers.<Advisor>any())).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(chatResponses);
    }

    /**
     * A turn the server reported on. The model name is deliberately not the one the call requested —
     * this field records what actually answered.
     */
    @SuppressWarnings("all")
    private static Flux<ChatResponse> reportedTurn(String text) {
        return Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage(text),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())),
                ChatResponseMetadata.builder()
                        .model(SERVER_REPORTED_MODEL)
                        .id("chatcmpl-1")
                        .usage(new DefaultUsage(300, 40, 340))
                        .build()));
    }

    private static ToolSlashCommand toolSlashCommand() {
        McpSchema.Tool mcpTool = mock(McpSchema.Tool.class);
        when(mcpTool.name()).thenReturn("search");
        when(mcpTool.description()).thenReturn("Search tool");

        return new ToolSlashCommand(mcpTool);
    }

    private static AttachmentResolution withImage(String fileName, String visionDescription) {
        ChatAttachmentDescription attachmentDescription =
                new ChatAttachmentDescription(UUID.randomUUID(), fileName, null, visionDescription);

        return new AttachmentResolution(List.of(attachmentDescription),
                "The user attached 1 image(s). " + fileName + ": " + visionDescription);
    }

    @Test
    void route_promptSlashCommand_fetchesTheMcpPromptAndStreams() {
        PromptSlashCommand promptCommand = new PromptSlashCommand("/ask", "ask", "Ask a question");

        McpSchema.TextContent userContent = new McpSchema.TextContent(null, "tell me something", null);
        McpSchema.PromptMessage userMessage = new McpSchema.PromptMessage(McpSchema.Role.USER, userContent);
        McpSchema.GetPromptResult getPromptResult =
                new McpSchema.GetPromptResult(null, List.of(userMessage), null);

        when(mcpClient.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(getPromptResult);
        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        stubPromptChain(Flux.just("answer"));

        StepVerifier.create(route(promptCommand, "tell me something", NO_ATTACHMENTS))
                .expectNext("answer")
                .verifyComplete();

        verify(a2aStickyAgentService).deactivate(chatId);
    }

    /**
     * The MCP-prompt route reaches a chat model like any other, so its turn is costed and attributed
     * like any other.
     */
    @Test
    void route_promptSlashCommand_persistsWhatTheServerReportedForTheTurn() {
        PromptSlashCommand promptCommand = new PromptSlashCommand("/ask", "ask", "Ask a question");

        McpSchema.TextContent userContent = new McpSchema.TextContent(null, "tell me something", null);
        McpSchema.PromptMessage userMessage = new McpSchema.PromptMessage(McpSchema.Role.USER, userContent);
        McpSchema.GetPromptResult getPromptResult =
                new McpSchema.GetPromptResult(null, List.of(userMessage), null);

        when(mcpClient.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(getPromptResult);
        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        stubPromptResponses(reportedTurn("answer"));

        StepVerifier.create(route(promptCommand, "tell me something", NO_ATTACHMENTS))
                .expectNext("answer")
                .verifyComplete();

        ArgumentCaptor<ResponseMetadata> metadataCaptor = ArgumentCaptor.captor();

        verify(chatMessageService).updateResponseMetadata(eq(chatId), any(ZonedDateTime.class),
                metadataCaptor.capture(), anyList());

        assertThat(metadataCaptor.getValue().model()).isEqualTo(SERVER_REPORTED_MODEL);
        assertThat(metadataCaptor.getValue().totalTokens()).isEqualTo(340);
    }

    /**
     * The A2A route never reaches a chat model, so it has nothing to record — and the tool routes
     * record their own turn inside {@link ToolCallService}, which uses a different model entirely.
     */
    @Test
    void route_agentSlashCommand_recordsNoResponseMetadata() {
        AgentSlashCommand agentCommand = new AgentSlashCommand("weather-agent", "weather-agent", "Weather");

        when(a2aStickyAgentService.activate(chatId, "weather-agent")).thenReturn(Mono.empty());
        when(a2aAgentService.delegate(eq(chatId), eq("weather-agent"), anyString(), anyString()))
                .thenReturn(Flux.just("a2a-result"));

        StepVerifier.create(route(agentCommand, "what is the weather?", NO_ATTACHMENTS))
                .expectNext("a2a-result")
                .verifyComplete();

        verifyNoInteractions(chatMessageService);
    }

    /**
     * The attachment block reaches this route as its own message inside the built prompt, never
     * folded into the user's words: the retrieval advisor rewrites only the last user message, and
     * anything inside that rewrite competes with the passages it is meant to introduce.
     */
    @Test
    void route_promptSlashCommand_carriesTheAttachmentBlockAsItsOwnMessage() {
        PromptSlashCommand promptCommand = new PromptSlashCommand("/ask", "ask", "Ask a question");

        McpSchema.TextContent userContent = new McpSchema.TextContent(null, "what is this?", null);
        McpSchema.PromptMessage userMessage = new McpSchema.PromptMessage(McpSchema.Role.USER, userContent);
        McpSchema.GetPromptResult getPromptResult =
                new McpSchema.GetPromptResult(null, List.of(userMessage), null);

        when(mcpClient.getPrompt(any(McpSchema.GetPromptRequest.class))).thenReturn(getPromptResult);
        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        stubPromptChain(Flux.just("a login screen"));

        StepVerifier.create(route(promptCommand, "what is this?", withImage("screenshot.png", "a login screen")))
                .expectNext("a login screen")
                .verifyComplete();

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).prompt(promptCaptor.capture());

        assertThat(promptCaptor.getValue().getInstructions())
                .anySatisfy(message -> assertThat(message.getText()).contains("screenshot.png"));

        //The user's own words survive as a message of their own.
        assertThat(promptCaptor.getValue().getInstructions())
                .anySatisfy(message -> assertThat(message.getText()).isEqualTo("what is this?"));
    }

    @Test
    void route_toolSlashCommand_releasesTheStickyAgentAndDelegates() {
        ToolSlashCommand toolCommand = toolSlashCommand();

        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        when(toolCallService.stream(eq(chatId), anyString(), eq(toolCommand), any()))
                .thenReturn(Flux.just("tool-result"));

        StepVerifier.create(route(toolCommand, "search for cats", NO_ATTACHMENTS))
                .expectNext("tool-result")
                .verifyComplete();

        verify(a2aStickyAgentService).deactivate(chatId);
        verify(toolCallService).stream(chatId, "search for cats", toolCommand, CONTEXT_MAP);
    }

    @Test
    void route_localToolSlashCommand_releasesTheStickyAgentAndDelegatesLocally() {
        LocalToolSlashCommand localToolCommand =
                new LocalToolSlashCommand("create_invoice", "create_invoice", "Create an invoice");

        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        when(toolCallService.streamLocal(eq(chatId), anyString(), eq(localToolCommand), any()))
                .thenReturn(Flux.just("local-tool-result"));

        StepVerifier.create(route(localToolCommand, "invoice Acme for 200", NO_ATTACHMENTS))
                .expectNext("local-tool-result")
                .verifyComplete();

        verify(a2aStickyAgentService).deactivate(chatId);
        verify(toolCallService).streamLocal(chatId, "invoice Acme for 200", localToolCommand, CONTEXT_MAP);
        verifyNoInteractions(mcpClient);
    }

    @Test
    void route_agentSlashCommand_activatesTheStickyAgentAndDelegates() {
        AgentSlashCommand agentCommand = new AgentSlashCommand("weather-agent", "weather-agent", "Weather");

        when(a2aStickyAgentService.activate(chatId, "weather-agent")).thenReturn(Mono.empty());
        when(a2aAgentService.delegate(eq(chatId), eq("weather-agent"), anyString(), anyString()))
                .thenReturn(Flux.just("a2a-result"));

        StepVerifier.create(route(agentCommand, "what is the weather?", NO_ATTACHMENTS))
                .expectNext("a2a-result")
                .verifyComplete();

        verify(a2aStickyAgentService).activate(chatId, "weather-agent");
        verify(a2aAgentService).delegate(chatId, "weather-agent", "what is the weather?", "token-abc");
    }

    /**
     * A remote agent takes a single string, so this is the one route where the described-images
     * block is inlined ahead of the user's words rather than carried separately.
     */
    @Test
    void route_agentSlashCommand_inlinesTheImageBlockAheadOfTheUsersWords() {
        AgentSlashCommand agentCommand = new AgentSlashCommand("weather-agent", "weather-agent", "Weather");

        when(a2aStickyAgentService.activate(chatId, "weather-agent")).thenReturn(Mono.empty());
        when(a2aAgentService.delegate(eq(chatId), eq("weather-agent"), anyString(), anyString()))
                .thenReturn(Flux.just("a2a-result"));

        StepVerifier.create(route(agentCommand, "what is this?", withImage("sky.png", "an overcast sky")))
                .expectNext("a2a-result")
                .verifyComplete();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(a2aAgentService).delegate(eq(chatId), eq("weather-agent"), messageCaptor.capture(), anyString());

        assertThat(messageCaptor.getValue())
                .contains("sky.png")
                .endsWith("what is this?");
    }

    /**
     * Image prose must not reach a tool's arguments — it turns a search for what the user asked
     * about into a search for whatever the vision model happened to say.
     */
    @Test
    void route_toolSlashCommand_withAttachments_sendsOnlyTheUsersOwnWords() {
        ToolSlashCommand toolCommand = toolSlashCommand();

        when(a2aStickyAgentService.deactivate(chatId)).thenReturn(Mono.empty());
        when(toolCallService.stream(eq(chatId), anyString(), eq(toolCommand), any()))
                .thenReturn(Flux.just("tool-result"));

        StepVerifier.create(route(toolCommand, "search for cats", withImage("cat.png", "a cat")))
                .expectNext("tool-result")
                .verifyComplete();

        verify(toolCallService).stream(chatId, "search for cats", toolCommand, CONTEXT_MAP);
    }

    private Flux<String> route(SlashCommand slashCommand,
                               String message,
                               AttachmentResolution attachments) {

        return slashCommandRouter.route(slashCommand, chatId, userId, message,
                attachments, CONTEXT_MAP, "token-abc");
    }
}
