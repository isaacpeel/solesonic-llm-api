package com.solesonic.service.prompt;

import com.solesonic.mcp.client.McpIdentityProvider;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ChatModelSelection;
import com.solesonic.service.litellm.LiteLlmHeaderRegistry;
import com.solesonic.service.image.ImageToolCatalog;
import com.solesonic.service.image.ReferenceImageInjector;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.rag.VectorStoreService;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.util.AttachmentContextFormatter;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    private static final String SERVER_REPORTED_MODEL = "qwen3-8b-instruct-q6";

    @Mock
    private ChatClient chatClient;
    @Mock
    private SlashCommandService slashCommandService;
    @Mock
    private SlashCommandRouter slashCommandRouter;
    @Mock
    private AttachmentContextResolver attachmentContextResolver;
    @Mock
    private A2AAgentService a2aAgentService;
    @Mock
    private A2AStickyAgentService a2aStickyAgentService;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private UserPreferencesService userPreferencesService;
    @Mock
    private McpIdentityProvider mcpIdentityProvider;
    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private Authentication authentication;
    @Mock
    private Jwt jwt;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamResponseSpec;

    @Mock
    private ImageToolCatalog imageToolCatalog;

    @Mock
    private ChatModelSelection chatModelSelection;

    private UUID chatId;
    private UUID userId;

    private PromptService promptService;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        promptService = new PromptService(
                chatClient,
                slashCommandService,
                slashCommandRouter,
                attachmentContextResolver,
                a2aAgentService,
                a2aStickyAgentService,
                chatModelSelection,
                vectorStoreService,
                userPreferencesService,
                mcpIdentityProvider,
                imageToolCatalog,
                chatMessageService,
                new LiteLlmHeaderRegistry(Clock.systemUTC()),
                "qwen3-8b",
                Duration.ofMinutes(30));

        ReflectionTestUtils.setField(promptService, "agentName", "Izzy");
        ReflectionTestUtils.setField(promptService, "defaultSystemPromptResource",
                new ClassPathResource("prompts/basic-system-prompt.st"));

        lenient().when(authentication.getPrincipal()).thenReturn(jwt);
        lenient().when(jwt.getTokenValue()).thenReturn("token-abc");

        //Nothing attached in most tests.
        lenient().when(attachmentContextResolver.resolve(any(), any(), any()))
                .thenReturn(new AttachmentResolution(List.of(), null));

        lenient().when(vectorStoreService.retrievalAugmentationAdvisor(any(UUID.class), any(UUID.class)))
                .thenReturn(mock(Advisor.class));

        lenient().when(userPreferencesService.getZone(any())).thenReturn(ZoneOffset.UTC);

        //No chat has picked a model in most tests.
        lenient().when(chatModelSelection.get(any())).thenReturn(Mono.empty());
    }

    private String requestedModel() {
        ArgumentCaptor<OpenAiChatOptions.Builder> optionsCaptor = ArgumentCaptor.captor();
        verify(requestSpec).options(optionsCaptor.capture());

        return optionsCaptor.getValue().build().getModel();
    }

    @Test
    void stream_withAModelSelectedForTheChat_streamsWithIt() {
        when(chatModelSelection.get(chatId)).thenReturn(Mono.just("openai/gpt-4o"));
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("hello"));

        StepVerifier.create(promptService.stream(chatId, userId, new ChatRequest("hello", Set.of(), Set.of(), null), authentication))
                .expectNext("hello")
                .verifyComplete();

        assertThat(requestedModel()).isEqualTo("openai/gpt-4o");
    }

    @Test
    void stream_withNoModelSelectedForTheChat_streamsWithTheDefault() {
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("hello"));

        StepVerifier.create(promptService.stream(chatId, userId, new ChatRequest("hello", Set.of(), Set.of(), null), authentication))
                .expectNext("hello")
                .verifyComplete();

        assertThat(requestedModel()).isEqualTo("qwen3-8b");
        verify(chatModelSelection).get(chatId);
    }

    /**
     * A selection is a convenience, not a requirement: a turn whose selection cannot be read still
     * gets an answer, from the default model.
     */
    @Test
    void stream_whenTheSelectionCannotBeRead_streamsWithTheDefault() {
        when(chatModelSelection.get(chatId)).thenReturn(Mono.error(new IllegalStateException("redis down")));
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("hello"));

        StepVerifier.create(promptService.stream(chatId, userId, new ChatRequest("hello", Set.of(), Set.of(), null), authentication))
                .expectNext("hello")
                .verifyComplete();

        assertThat(requestedModel()).isEqualTo("qwen3-8b");
    }

    @Test
    void stream_withASlashCommand_handsTheRouterTheChatsModel() {
        McpSchema.Tool mcpTool = mock(McpSchema.Tool.class);
        when(mcpTool.name()).thenReturn("search");
        when(mcpTool.description()).thenReturn("Search tool");
        ToolSlashCommand toolCommand = new ToolSlashCommand(mcpTool);

        when(chatModelSelection.get(chatId)).thenReturn(Mono.just("openai/gpt-4o"));
        when(slashCommandService.commands(Set.of("search"))).thenReturn(List.of(toolCommand));
        when(slashCommandRouter.route(eq(toolCommand), eq(chatId), eq(userId), anyString(),
                any(), any(), anyString(), anyString())).thenReturn(Flux.just("tool-result"));

        StepVerifier.create(promptService.stream(chatId, userId,
                        new ChatRequest("search for cats", Set.of("search"), Set.of(), null), authentication))
                .expectNext("tool-result")
                .verifyComplete();

        verify(slashCommandRouter).route(eq(toolCommand), eq(chatId), eq(userId), eq("search for cats"),
                any(AttachmentResolution.class), any(), eq("token-abc"), eq("openai/gpt-4o"));
    }

    private void stubBasicPromptChain(Flux<String> emissions) {
        stubBasicPromptResponses(chatResponsesOf(emissions));
    }

    private void stubBasicPromptResponses(Flux<ChatResponse> chatResponses) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(mcpIdentityProvider.getToolCallbacks(ArgumentMatchers.anySet())).thenReturn(List.of());
        when(requestSpec.tools(ArgumentMatchers.any())).thenReturn(requestSpec);
        lenient().when(requestSpec.messages(ArgumentMatchers.<Message>any())).thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any()))
                .thenReturn(requestSpec);
        lenient().when(requestSpec.advisors(ArgumentMatchers.<Advisor>any())).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(chatResponses);
    }

    private static Flux<ChatResponse> chatResponsesOf(Flux<String> emissions) {
        return emissions.map(text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    /**
     * A turn the server reported on, in the shape a llama.cpp-style server closes one with: the
     * answer and the finish reason on one response, the counts on it too. The model name is
     * deliberately not the one the call requested — recording what actually answered is the whole
     * reason {@code responseMetadata.model} exists separately from the configured model.
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

    private void resolvesToImage(String fileName, String visionDescription) {
        ChatAttachmentDescription attachmentDescription =
                new ChatAttachmentDescription(UUID.randomUUID(), fileName, null, visionDescription);

        List<ChatAttachmentDescription> descriptions = List.of(attachmentDescription);

        when(attachmentContextResolver.resolve(any(), any(), any())).thenReturn(
                new AttachmentResolution(descriptions, AttachmentContextFormatter.context(descriptions)));
    }

    /**
     * The token is taken before any attachment work, so a principal that cannot supply one fails
     * ahead of several seconds of vision and embedding calls rather than behind them.
     */
    @Test
    void stream_withNonJwtPrincipal_throwsBeforeResolvingAttachments() {
        when(authentication.getPrincipal()).thenReturn("not-a-jwt");
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of(), null);

        assertThatThrownBy(() -> promptService.stream(chatId, userId, chatRequest, authentication).blockFirst())
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(attachmentContextResolver);
    }

    @Test
    void stream_withNoCommandsAndStickyAgentPresent_delegatesToA2AAgent() {
        ChatRequest chatRequest = new ChatRequest("what is the weather?", Set.of(), Set.of(), null);
        when(a2aStickyAgentService.getActiveAgent(chatId))
                .thenReturn(Mono.just(Optional.of("weather-agent")));
        when(a2aAgentService.delegate(eq(chatId), eq("weather-agent"), anyString(), anyString()))
                .thenReturn(Flux.just("forecast"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("forecast")
                .verifyComplete();

        verify(a2aAgentService).delegate(chatId, "weather-agent", "what is the weather?", "token-abc");
        verifyNoInteractions(slashCommandRouter);

        //A remote agent has no token accounting to report, so this turn must leave the column null
        //rather than write an empty record over it.
        verifyNoInteractions(chatMessageService);
    }

    /**
     * The whole point of the column: what the server said answered, which a router or fallback group
     * can make a different model from the one the call asked for.
     */
    @Test
    void stream_withNoCommandsAndNoStickyAgent_persistsWhatTheServerReportedForTheTurn() {
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of(), null);
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));

        AdvisorCaptureFeed advisorCaptureFeed = new AdvisorCaptureFeed();
        stubBasicPromptResponses(advisorCaptureFeed.feeding(reportedTurn("hi there")));
        advisorCaptureFeed.readAdvisorParamsOf(requestSpec);

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("hi there")
                .verifyComplete();

        ArgumentCaptor<ResponseMetadata> metadataCaptor = ArgumentCaptor.captor();

        verify(chatMessageService).updateResponseMetadata(eq(chatId), any(ZonedDateTime.class),
                metadataCaptor.capture(), anyList());

        ResponseMetadata responseMetadata = metadataCaptor.getValue();

        assertThat(responseMetadata.model()).isEqualTo(SERVER_REPORTED_MODEL);
        assertThat(responseMetadata.model()).isNotEqualTo("qwen3-8b");
        assertThat(responseMetadata.totalTokens()).isEqualTo(340);
        assertThat(responseMetadata.modelCalls()).isEqualTo(1);
    }

    @Test
    void stream_withNoCommandsAndNoStickyAgent_routesToBasicPrompt() {
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of(), null);
        when(a2aStickyAgentService.getActiveAgent(chatId))
                .thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("hello"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("hello")
                .verifyComplete();

        //The basic prompt is a classpath template, not an MCP fetch — the MCP round trip that used to
        //happen here is gone, and a regression that reinstated it would cost every turn a call.
        verify(requestSpec).system(anyString());
        verifyNoInteractions(slashCommandRouter);
    }

    /**
     * The routing decision is all this class makes for a slash command: the command, the user's own
     * words and the resolved attachments go to the router untouched.
     */
    @Test
    void stream_withASlashCommand_handsItToTheRouterWithTheUsersOwnWords() {
        McpSchema.Tool mcpTool = mock(McpSchema.Tool.class);
        when(mcpTool.name()).thenReturn("search");
        when(mcpTool.description()).thenReturn("Search tool");
        ToolSlashCommand toolCommand = new ToolSlashCommand(mcpTool);

        ChatRequest chatRequest = new ChatRequest("search for cats", Set.of("search"), Set.of(), null);

        when(slashCommandService.commands(Set.of("search"))).thenReturn(List.of(toolCommand));
        when(slashCommandRouter.route(eq(toolCommand), eq(chatId), eq(userId), anyString(),
                any(), any(), anyString(), anyString())).thenReturn(Flux.just("tool-result"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("tool-result")
                .verifyComplete();

        verify(slashCommandRouter).route(eq(toolCommand), eq(chatId), eq(userId), eq("search for cats"),
                any(AttachmentResolution.class), any(), eq("token-abc"), eq("qwen3-8b"));

        //A slash command never reaches the sticky-agent lookup: the router owns that bookkeeping.
        verify(a2aStickyAgentService, never()).getActiveAgent(any());
    }

    /**
     * The tool context every route is handed carries the user's own token and both ids — the MCP
     * tools called mid-turn have no other way to act as the user, and the image interceptor cannot
     * store a generated image without them.
     */
    @Test
    void stream_buildsTheToolContextFromTheRequestsOwnTokenAndIds() {
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of(), null);
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("hello"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("hello")
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.captor();
        verify(requestSpec).toolContext(contextCaptor.capture());

        assertThat(contextCaptor.getValue())
                .containsEntry("userToken", "token-abc")
                .containsEntry("userId", userId)
                .containsEntry(PromptService.CHAT_ID, chatId)
                .containsEntry(PromptService.PROGRESS_TOKEN, chatId)
                .containsEntry(ReferenceImageInjector.REFERENCE_ATTACHMENT_IDS, Set.of());
    }

    /**
     * Image tools are whatever workflows the MCP server offers — none named here — so the model is
     * offered exactly those alongside the fixed tools.
     */
    @Test
    void stream_offersTheModelEveryConfiguredImageTool() {
        when(imageToolCatalog.toolNames()).thenReturn(Set.of("generate_image_flux", "kontext_edit"));
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("hello"));

        StepVerifier.create(promptService.stream(chatId, userId, new ChatRequest("hello", Set.of(), Set.of(), null), authentication))
                .expectNext("hello")
                .verifyComplete();

        ArgumentCaptor<Set<String>> toolNamesCaptor = ArgumentCaptor.captor();
        verify(mcpIdentityProvider).getToolCallbacks(toolNamesCaptor.capture());

        assertThat(toolNamesCaptor.getValue())
                .contains("web_search", "generate_image_flux", "kontext_edit")
                .doesNotContain("generate_image");
    }

    /**
     * The send's image attachment ids ride in the tool context, never in the prompt, so the image
     * tool can be handed the bytes without the model ever seeing them.
     */
    @Test
    void stream_putsTheSendsImageIdsInTheToolContext() {
        UUID imageId = UUID.randomUUID();
        ChatRequest chatRequest = new ChatRequest("make it a watercolor", Set.of(), Set.of(imageId), null);
        when(attachmentContextResolver.resolve(any(), any(), any()))
                .thenReturn(new AttachmentResolution(List.of(), null, Set.of(imageId)));
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("done"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("done")
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.captor();
        verify(requestSpec).toolContext(contextCaptor.capture());

        assertThat(contextCaptor.getValue())
                .containsEntry(ReferenceImageInjector.REFERENCE_ATTACHMENT_IDS, Set.of(imageId));
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
        ChatRequest chatRequest = new ChatRequest("what is this?", Set.of(), Set.of(attachmentId), null);

        resolvesToImage("screenshot.png", "a login screen");
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("that is a login screen"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("that is a login screen")
                .verifyComplete();

        verify(attachmentContextResolver).resolve(chatId, userId, Set.of(attachmentId));
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

        //The system prompt is rendered from a classpath template, so the description block reaches the
        //model only as its own message -- never folded into the user's words.
        verify(requestSpec).system(anyString());
    }

    @Test
    void stream_withoutAttachments_addsNoImageContextMessage() {
        ChatRequest chatRequest = new ChatRequest("plain question", Set.of(), Set.of(), null);

        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.empty()));
        stubBasicPromptChain(Flux.just("an answer"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("an answer")
                .verifyComplete();

        verify(requestSpec).user("plain question");
        verify(requestSpec, never()).messages(ArgumentMatchers.<Message>any());
    }

    /**
     * The sticky-agent route has no message structure to put a separate block into — a remote agent
     * takes a single string — so the described images are inlined ahead of the user's words.
     */
    @Test
    void stream_withStickyAgentAndAttachments_inlinesTheImageBlock() {
        UUID attachmentId = UUID.randomUUID();
        ChatRequest chatRequest = new ChatRequest("what is this?", Set.of(), Set.of(attachmentId), null);

        resolvesToImage("sky.png", "an overcast sky");
        when(a2aStickyAgentService.getActiveAgent(chatId))
                .thenReturn(Mono.just(Optional.of("weather-agent")));
        when(a2aAgentService.delegate(eq(chatId), eq("weather-agent"), anyString(), anyString()))
                .thenReturn(Flux.just("forecast"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("forecast")
                .verifyComplete();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(a2aAgentService).delegate(eq(chatId), eq("weather-agent"), messageCaptor.capture(), anyString());

        assertThat(messageCaptor.getValue())
                .contains("sky.png")
                .endsWith("what is this?");
    }

    /**
     * A command that resolves to nothing must fail rather than silently fall through to the default
     * LLM path answering a question the user did not ask.
     */
    @Test
    void stream_withAnUnresolvableCommand_throwsIllegalState() {
        ChatRequest chatRequest = new ChatRequest("do the thing", Set.of("/unknown"), Set.of(), null);
        when(slashCommandService.commands(Set.of("/unknown"))).thenReturn(List.of());

        assertThatThrownBy(() -> promptService.stream(chatId, userId, chatRequest, authentication).blockFirst())
                .isInstanceOf(IllegalStateException.class);
    }
}
