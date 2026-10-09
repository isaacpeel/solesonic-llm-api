package com.solesonic.api.chat;

import com.agui.community.core.message.ToolMessage;
import com.solesonic.exception.chat.ChatModelException;
import com.solesonic.exception.handler.AttachmentExceptionHandler;
import com.solesonic.exception.handler.ChatModelExceptionHandler;
import com.solesonic.mcp.client.elicitation.ElicitationProvider;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.model.ChatModelErrorCode;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.service.chat.ChatModelService;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.chat.ChatStreamAccessService;
import com.solesonic.service.chat.ChatStreamAccessService.ChatAccess;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.redis.RedisStreamingChatService;
import com.solesonic.service.redis.RedisStreamingChatService.CancelOutcome;
import com.solesonic.service.redis.StreamResumeService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the status-code contract of {@link StreamingChatController#cancel}: ownership is checked the
 * same way {@code update} and {@code resume} already check it, and the outcome from
 * {@link RedisStreamingChatService#cancel} maps to a status without ever blocking on it.
 */
@ExtendWith(MockitoExtension.class)
class StreamingChatControllerTest {

    @Mock
    private RedisStreamingChatService streamingChatService;

    @Mock
    private ElicitationService elicitationService;

    @Mock
    private StreamResumeService streamResumeService;

    @Mock
    private ChatStreamAccessService chatStreamAccessService;

    @Mock
    private ChatService chatService;

    @Mock
    private ChatModelService chatModelService;

    @Mock
    private Authentication authentication;

    private StreamingChatController streamingChatController;

    private UUID chatId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        streamingChatController = new StreamingChatController(streamingChatService, elicitationService,
                streamResumeService, chatStreamAccessService, chatService, chatModelService);
    }

    @Test
    void deniesCancellingAChatTheCallerDoesNotOwn() {
        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.FORBIDDEN);

        StepVerifier.create(streamingChatController.cancel(chatId, userId, authentication))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verifyComplete();

        verify(streamingChatService, never()).cancel(any(), any());
    }

    @Test
    void propagatesNotFoundForAnUnknownChat() {
        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.NOT_FOUND);

        StepVerifier.create(streamingChatController.cancel(chatId, userId, authentication))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                .verifyComplete();

        verify(streamingChatService, never()).cancel(any(), any());
    }

    @Test
    void acceptsCancellingATurnInFlight() {
        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.GRANTED);
        when(streamingChatService.cancel(chatId, userId)).thenReturn(Mono.just(CancelOutcome.CANCEL_REQUESTED));

        StepVerifier.create(streamingChatController.cancel(chatId, userId, authentication))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED))
                .verifyComplete();
    }

    @Test
    void completesAnElicitationOnAnOwnedChat() {
        UUID elicitationId = UUID.randomUUID();
        ToolMessage toolMessage = toolMessage(elicitationId.toString());
        ElicitationProvider.ElicitationActionResult actionResult =
                new ElicitationProvider.ElicitationActionResult(McpSchema.ElicitResult.Action.ACCEPT, chatId, elicitationId, Map.of());

        when(elicitationService.actionResult(chatId, elicitationId, toolMessage)).thenReturn(Optional.of(actionResult));
        when(elicitationService.completeFromFrontend(actionResult)).thenReturn(Mono.just(true));

        StepVerifier.create(streamingChatController.submitElicitationResponse(chatId, elicitationId, toolMessage))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();

        verify(chatService).requireOwned(chatId);
    }

    /**
     * The chat id is attacker-controlled. Answering someone else's elicitation has to fail at the
     * ownership check, before the answer reaches the parked tool call.
     */
    @Test
    void refusesAnElicitationResponseForAChatTheCallerDoesNotOwn() {
        UUID elicitationId = UUID.randomUUID();

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(chatService).requireOwned(chatId);

        assertThatThrownBy(() -> StepVerifier.create(streamingChatController
                        .submitElicitationResponse(chatId, elicitationId, toolMessage(elicitationId.toString())))
                .verifyComplete())
                .isInstanceOf(ResponseStatusException.class);

        verify(elicitationService, never()).completeFromFrontend(any());
    }

    @Test
    void rejectsAToolCallIdThatDoesNotMatchThePath() {
        UUID elicitationId = UUID.randomUUID();

        StepVerifier.create(streamingChatController
                        .submitElicitationResponse(chatId, elicitationId, toolMessage(UUID.randomUUID().toString())))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();

        verify(elicitationService, never()).completeFromFrontend(any());
    }

    @Test
    void rejectsAToolMessageWhoseContentNamesNoAction() {
        UUID elicitationId = UUID.randomUUID();
        ToolMessage toolMessage = toolMessage(elicitationId.toString());

        when(elicitationService.actionResult(chatId, elicitationId, toolMessage)).thenReturn(Optional.empty());

        StepVerifier.create(streamingChatController.submitElicitationResponse(chatId, elicitationId, toolMessage))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();

        verify(elicitationService, never()).completeFromFrontend(any());
    }

    private static ToolMessage toolMessage(String toolCallId) {
        return new ToolMessage("message-1", "{\"action\":\"ACCEPT\"}", toolCallId);
    }

    @Test
    void answersNoContentWhenThereIsNothingToCancel() {
        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.GRANTED);
        when(streamingChatService.cancel(chatId, userId)).thenReturn(Mono.just(CancelOutcome.NOTHING_TO_CANCEL));

        StepVerifier.create(streamingChatController.cancel(chatId, userId, authentication))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT))
                .verifyComplete();
    }

    /**
     * A model picked before the chat existed is stored under the endpoint's spelling, which is the one
     * a completion request has to carry.
     */
    @Test
    void createsAChatOnTheResolvedSpellingOfTheRequestedModel() {
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of(), "OPENAI/GPT-4O");

        when(chatStreamAccessService.forNewChat(authentication, userId)).thenReturn(ChatAccess.GRANTED);
        when(chatModelService.resolve("OPENAI/GPT-4O")).thenReturn("openai/gpt-4o");
        when(streamingChatService.create(userId, chatRequest, "openai/gpt-4o", authentication)).thenReturn(Flux.empty());

        assertThat(streamingChatController.create(userId, chatRequest, authentication).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        verify(streamingChatService).create(userId, chatRequest, "openai/gpt-4o", authentication);
    }

    @Test
    void createsAChatOnTheDefaultWhenNoModelIsRequested() {
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of(), null);

        when(chatStreamAccessService.forNewChat(authentication, userId)).thenReturn(ChatAccess.GRANTED);
        when(streamingChatService.create(userId, chatRequest, null, authentication)).thenReturn(Flux.empty());

        streamingChatController.create(userId, chatRequest, authentication);

        verify(chatModelService, never()).resolve(any());
        verify(streamingChatService).create(userId, chatRequest, null, authentication);
    }

    /**
     * Validated before the SSE response starts, so a client asking for an event stream still gets a
     * status code and a coded body — and no chat is created.
     */
    @Test
    void refusesAnUnknownModelBeforeCreatingAChat() throws Exception {
        when(chatStreamAccessService.forNewChat(any(), any())).thenReturn(ChatAccess.GRANTED);
        when(chatModelService.resolve("nope"))
                .thenThrow(new ChatModelException(ChatModelErrorCode.UNKNOWN_MODEL, "The model nope is not offered."));

        mockMvc().perform(post("/streaming/chats/users/{userId}", userId)
                        .principal(authentication)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatMessage\":\"hello\",\"model\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_MODEL"));

        verify(streamingChatService, never()).create(any(), any(), any(), any());
    }

    @Test
    void answersServiceUnavailableWhenTheModelListCannotBeFetched() throws Exception {
        when(chatStreamAccessService.forNewChat(any(), any())).thenReturn(ChatAccess.GRANTED);
        when(chatModelService.resolve("openai/gpt-4o")).thenThrow(
                new ChatModelException(ChatModelErrorCode.MODEL_LIST_UNAVAILABLE, "The model list could not be retrieved."));

        mockMvc().perform(post("/streaming/chats/users/{userId}", userId)
                        .principal(authentication)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatMessage\":\"hello\",\"model\":\"openai/gpt-4o\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_LIST_UNAVAILABLE"));

        verify(streamingChatService, never()).create(any(), any(), any(), any());
    }

    /**
     * An existing chat changes model through {@code PUT /chats/{chatId}/model} and nowhere else.
     */
    @Test
    void refusesAModelOnAnExistingChat() {
        ChatRequest chatRequest = new ChatRequest("hello", Set.of(), Set.of(), "openai/gpt-4o");

        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.GRANTED);

        assertThatThrownBy(() -> streamingChatController.update(userId, chatId, chatRequest, null, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(streamingChatService, never()).update(any(), any(), any(), any());
    }

    /**
     * {@code /model} is applied through REST. Refused before anything is persisted, since by the time
     * a turn could reject it the user message is written and {@code RUN_STARTED} is out.
     */
    @Test
    void refusesTheModelCommandOnANewChat() {
        ChatRequest chatRequest = new ChatRequest("openai/gpt-4o", Set.of(SlashCommand.MODEL), Set.of(), null);

        when(chatStreamAccessService.forNewChat(authentication, userId)).thenReturn(ChatAccess.GRANTED);

        assertThatThrownBy(() -> streamingChatController.create(userId, chatRequest, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(streamingChatService, never()).create(any(), any(), any(), any());
    }

    /**
     * A streaming client asks for {@code text/event-stream} only, and still has to be told why.
     */
    @Test
    void explainsARefusedSettingCommandToAStreamingClient() throws Exception {
        when(chatStreamAccessService.forNewChat(any(), any())).thenReturn(ChatAccess.GRANTED);

        mockMvc().perform(post("/streaming/chats/users/{userId}", userId)
                        .principal(authentication)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatMessage\":\"\",\"commands\":[\"model\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(streamingChatService, never()).create(any(), any(), any(), any());
    }

    @Test
    void refusesTheModelCommandOnAnExistingChat() {
        ChatRequest chatRequest = new ChatRequest("openai/gpt-4o", Set.of(SlashCommand.MODEL), Set.of(), null);

        when(chatStreamAccessService.forExistingChat(authentication, chatId, userId)).thenReturn(ChatAccess.GRANTED);

        assertThatThrownBy(() -> streamingChatController.update(userId, chatId, chatRequest, null, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(streamingChatService, never()).update(any(), any(), any(), any());
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(streamingChatController)
                .setControllerAdvice(new ChatModelExceptionHandler(), new AttachmentExceptionHandler())
                .build();
    }
}
