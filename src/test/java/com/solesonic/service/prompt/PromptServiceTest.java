package com.solesonic.service.prompt;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.rag.VectorStoreService;
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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                slashCommandService,
                slashCommandRouter,
                attachmentContextResolver,
                a2aAgentService,
                a2aStickyAgentService,
                vectorStoreService,
                "Izzy",
                "qwen3-8b",
                new ClassPathResource("prompts/basic-system-prompt.st"));

        lenient().when(authentication.getPrincipal()).thenReturn(jwt);
        lenient().when(jwt.getTokenValue()).thenReturn("token-abc");

        //Nothing attached in most tests.
        lenient().when(attachmentContextResolver.resolve(any(), any(), any()))
                .thenReturn(new AttachmentResolution(List.of(), null));

        lenient().when(vectorStoreService.retrievalAugmentationAdvisor(any(UUID.class), any(UUID.class)))
                .thenReturn(mock(Advisor.class));
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
        when(streamResponseSpec.chatResponse()).thenReturn(chatResponsesOf(emissions));
    }

    private static Flux<ChatResponse> chatResponsesOf(Flux<String> emissions) {
        return emissions.map(text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
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
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of());

        assertThatThrownBy(() -> promptService.stream(chatId, userId, chatRequest, authentication).blockFirst())
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(attachmentContextResolver);
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

        verify(a2aAgentService).delegate(chatId, "weather-agent", "what is the weather?", "token-abc");
        verifyNoInteractions(slashCommandRouter);
    }

    @Test
    void stream_withNoCommandsAndNoStickyAgent_routesToBasicPrompt() {
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of());
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

        ChatRequest chatRequest = new ChatRequest("search for cats", Set.of("search"), Set.of());

        when(slashCommandService.commands(Set.of("search"))).thenReturn(List.of(toolCommand));
        when(slashCommandRouter.route(eq(toolCommand), eq(chatId), eq(userId), anyString(),
                any(), any(), anyString())).thenReturn(Flux.just("tool-result"));

        StepVerifier.create(promptService.stream(chatId, userId, chatRequest, authentication))
                .expectNext("tool-result")
                .verifyComplete();

        verify(slashCommandRouter).route(eq(toolCommand), eq(chatId), eq(userId), eq("search for cats"),
                any(AttachmentResolution.class), any(), eq("token-abc"));

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
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of());
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
                .containsEntry(PromptService.PROGRESS_TOKEN, chatId);
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
        ChatRequest chatRequest = new ChatRequest("plain question", Set.of(), Set.of());

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
        ChatRequest chatRequest = new ChatRequest("what is this?", Set.of(), Set.of(attachmentId));

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
        ChatRequest chatRequest = new ChatRequest("do the thing", Set.of("/unknown"), Set.of());
        when(slashCommandService.commands(Set.of("/unknown"))).thenReturn(List.of());

        assertThatThrownBy(() -> promptService.stream(chatId, userId, chatRequest, authentication).blockFirst())
                .isInstanceOf(IllegalStateException.class);
    }
}
