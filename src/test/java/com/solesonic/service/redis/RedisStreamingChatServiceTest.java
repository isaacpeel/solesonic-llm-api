package com.solesonic.service.redis;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.CustomEvent;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.event.RunFinishedEvent;
import com.agui.community.core.event.RunStartedEvent;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageEndEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.event.ToolCallStartEvent;
import com.agui.community.core.message.Role;
import com.agui.community.core.message.UserMessage;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;
import com.solesonic.config.JacksonConfig;
import com.solesonic.exception.ChatException;
import com.solesonic.exception.a2a.A2AAgentUnavailableException;
import com.solesonic.exception.google.GoogleApiException;
import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.ModelCallMetadata;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.TurnErrorCode;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.image.ImageGenerationErrorCode;
import com.solesonic.model.push.PushEvent;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ChatModelSelection;
import com.solesonic.service.chat.PendingMessageMetadata;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.chat.events.SideChannelMessage;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.prompt.PromptService;
import com.solesonic.service.push.PushNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.solesonic.service.chat.events.ElicitationService.CANCEL_ACTION;
import static com.solesonic.service.chat.events.ElicitationService.ELICITATION;
import static com.solesonic.service.redis.RedisStreamingChatService.CHAT_CANCELED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.ai.chat.messages.MessageType.SYSTEM;

/**
 * Pins the turn lifecycle of {@link RedisStreamingChatService} as AG-UI frames: one run, at most one
 * text message, and exactly one terminal frame per turn, of the right kind, published before cleanup
 * runs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisStreamingChatServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    private static final String TEXT_MESSAGE_START = "TEXT_MESSAGE_START";
    private static final String TEXT_MESSAGE_CONTENT = "TEXT_MESSAGE_CONTENT";
    private static final String TEXT_MESSAGE_END = "TEXT_MESSAGE_END";
    private static final String RUN_STARTED = "RUN_STARTED";
    private static final String RUN_FINISHED = "RUN_FINISHED";
    private static final String RUN_ERROR = "RUN_ERROR";
    private static final String CUSTOM = "CUSTOM";

    private static final JsonMapper JSON_MAPPER = new JacksonConfig().jsonMapper();

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private PromptService promptService;

    @Mock
    private ElicitationService elicitationService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private RedisStreamService redisStreamService;

    @Mock
    private ActiveStreamTracker activeStreamTracker;

    @Mock
    private NotificationService notificationService;

    @Mock
    private GeneratedImageService generatedImageService;

    @Mock
    private ChatModelSelection chatModelSelection;

    @Mock
    private Authentication authentication;

    @Mock
    private PushNotificationService pushNotificationService;

    private final PendingMessageMetadata pendingMessageMetadata = spy(new PendingMessageMetadata());

    private RedisStreamingChatService redisStreamingChatService;

    private final List<PublishedEvent> published = new CopyOnWriteArrayList<>();

    private record PublishedEvent(String type, Object payload) {
    }

    @BeforeEach
    void setUp() {
        redisStreamingChatService = new RedisStreamingChatService(chatRepository,
                promptService,
                elicitationService,
                chatMessageService,
                redisStreamService,
                activeStreamTracker,
                notificationService,
                generatedImageService,
                new SideChannelEventTranslator(JSON_MAPPER),
                chatModelSelection,
                pushNotificationService,
                pendingMessageMetadata);

        published.clear();

        when(redisStreamService.publish(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            published.add(new PublishedEvent(invocation.getArgument(2), invocation.getArgument(3)));
            return Mono.just(RecordId.of("1-0"));
        });

        when(activeStreamTracker.put(any(), any())).thenReturn(Mono.just(true));
        when(activeStreamTracker.remove(any(), any())).thenReturn(Mono.just(true));
        when(generatedImageService.forChatSince(any(), any())).thenReturn(List.of());
        when(chatMessageService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(elicitationService.registerChat(CHAT_ID)).thenReturn(Flux.never());

        when(notificationService.recordFailure(any(), anyString(), any())).thenAnswer(invocation ->
                Map.of("message", invocation.getArgument(1),
                        "code", ((TurnErrorCode) invocation.getArgument(2)).wireValue()));
    }

    private void errorTurn(Throwable throwable) {
        when(promptService.stream(any(), any(), any(), any())).thenReturn(Flux.error(throwable));

        runTurn();
    }

    private RunErrorEvent publishedRunError() {
        return (RunErrorEvent) eventsOfType(RUN_ERROR).getFirst().payload();
    }

    private static WebClientResponseException webClientResponseException(int status) {
        return WebClientResponseException.create(status, "status " + status, HttpHeaders.EMPTY, new byte[0], null);
    }

    private void runTurn() {
        StepVerifier.create(redisStreamingChatService
                        .runTurn(CHAT_ID, USER_ID, RUN_ID, new ChatRequest("hello", Set.of(), Set.of(), null), authentication))
                .verifyComplete();
    }

    private void modelStreams(String... chunks) {
        when(promptService.stream(any(), any(), any(), any())).thenReturn(Flux.just(chunks));
    }

    private static SideChannelMessage cancelEvent() {
        return new SideChannelMessage(CANCEL_ACTION, JSON_MAPPER.valueToTree(CANCEL_ACTION));
    }

    private List<String> publishedTypes() {
        return published.stream().map(PublishedEvent::type).toList();
    }

    private List<PublishedEvent> eventsOfType(String type) {
        return published.stream().filter(event -> type.equals(event.type())).toList();
    }

    private RunFinishedEvent runFinished() {
        List<PublishedEvent> finishedEvents = eventsOfType(RUN_FINISHED);
        assertThat(finishedEvents).hasSize(1);
        assertThat(finishedEvents.getFirst().payload()).isInstanceOf(RunFinishedEvent.class);
        return (RunFinishedEvent) finishedEvents.getFirst().payload();
    }

    private ChatMessage finishedMessage() {
        assertThat(runFinished().result()).isInstanceOf(SolesonicChatResponse.class);
        return ((SolesonicChatResponse) runFinished().result()).message();
    }

    /**
     * The normal path. Also the canary for a turn that never terminates: an outcome branch that
     * re-subscribes to the cancel signal hangs here instead of completing.
     */
    @Test
    void normalTurnIsOneTextMessageThenRunFinished() {
        modelStreams("Hello ", "world");

        runTurn();

        assertThat(publishedTypes()).containsExactly(
                TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END, RUN_FINISHED);

        TextMessageStartEvent start = (TextMessageStartEvent) eventsOfType(TEXT_MESSAGE_START).getFirst().payload();
        assertThat(start.role()).isEqualTo(Role.ASSISTANT);

        List<TextMessageContentEvent> contents = eventsOfType(TEXT_MESSAGE_CONTENT).stream()
                .map(event -> (TextMessageContentEvent) event.payload())
                .toList();
        assertThat(contents).allSatisfy(content -> assertThat(content.messageId()).isEqualTo(start.messageId()));
        assertThat(contents).extracting(TextMessageContentEvent::delta).containsExactly("Hello ", "world");

        TextMessageEndEvent end = (TextMessageEndEvent) eventsOfType(TEXT_MESSAGE_END).getFirst().payload();
        assertThat(end.messageId()).isEqualTo(start.messageId());
    }

    /**
     * Metadata a route left pending rides on the start frame, which otherwise serializes exactly as
     * the SDK's own record does.
     */
    @Test
    void pendingMetadataRidesOnTheStartFrame() {
        pendingMessageMetadata.put(CHAT_ID, "solesonic/sound", Map.of("cueId", "sound-1", "playOnce", true));
        modelStreams("Hello");

        runTurn();

        assertThat(publishedTypes()).containsExactly(TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END, RUN_FINISHED);

        String messageId = ((TextMessageContentEvent) eventsOfType(TEXT_MESSAGE_CONTENT).getFirst().payload()).messageId();
        JsonNode frame = JSON_MAPPER.valueToTree(eventsOfType(TEXT_MESSAGE_START).getFirst().payload());

        assertThat(frame.at("/metadata/solesonic~1sound/cueId").asString()).isEqualTo("sound-1");
        assertThat(frame.at("/metadata/solesonic~1sound/playOnce").asBoolean()).isTrue();

        ObjectNode withoutMetadata = (ObjectNode) frame.deepCopy();
        withoutMetadata.remove("metadata");
        assertThat(withoutMetadata).isEqualTo(JSON_MAPPER.valueToTree(new TextMessageStartEvent(messageId, Role.ASSISTANT, null, null)));
        assertThat(JSON_MAPPER.writeValueAsString(eventsOfType(TEXT_MESSAGE_START).getFirst().payload()))
                .startsWith("{\"type\":\"TEXT_MESSAGE_START\"");
    }

    @Test
    void withoutPendingMetadataTheStartFrameIsTheSdksOwn() {
        modelStreams("Hello");

        runTurn();

        Object start = eventsOfType(TEXT_MESSAGE_START).getFirst().payload();
        assertThat(start).isInstanceOf(TextMessageStartEvent.class);
        assertThat(JSON_MAPPER.valueToTree(start).has("metadata")).isFalse();
    }

    @Test
    void metadataATurnNeverUsedDoesNotReachTheNextTurn() {
        pendingMessageMetadata.put(CHAT_ID, "solesonic/sound", Map.of("cueId", "sound-1"));
        modelStreams();

        runTurn();

        //Cleanup runs in doFinally, after the completion the verifier waits on.
        verify(pendingMessageMetadata, timeout(1000)).clear(CHAT_ID);
        assertThat(pendingMessageMetadata.take(CHAT_ID)).isEmpty();
    }

    @Test
    void runFinishedCarriesTheRunAndTheAssistantMessage() {
        modelStreams("Hello ", "world");

        runTurn();

        assertThat(runFinished().threadId()).isEqualTo(CHAT_ID.toString());
        assertThat(runFinished().runId()).isEqualTo(RUN_ID.toString());

        ChatMessage responseMessage = finishedMessage();
        assertThat(responseMessage.getMessageType()).isEqualTo(ASSISTANT);
        assertThat(responseMessage.getMessage()).isEqualTo("Hello world");
        assertThat(responseMessage.getChatId()).isEqualTo(CHAT_ID);
    }

    /**
     * A text message with no content is not a message. A turn the model answered with nothing goes
     * straight to the terminal frame rather than opening and closing an empty one.
     */
    @Test
    void emptyTurnOpensNoTextMessage() {
        modelStreams();

        runTurn();

        assertThat(publishedTypes()).containsExactly(RUN_FINISHED);
    }

    @Test
    void normalTurnAttachesImagesGeneratedDuringTheTurn() {
        modelStreams("drawing");

        runTurn();

        verify(generatedImageService).forChatSince(eq(CHAT_ID), any());
        assertThat(finishedMessage().getGeneratedImages()).isEmpty();
    }

    /**
     * The finished message is built from scratch rather than read from the row the chat memory
     * advisor wrote, so what the model server reported has to be fetched back onto it — otherwise the
     * accounting lands in history but is null on the frame the client finalises the turn with.
     */
    @Test
    void normalTurnAttachesWhatTheServerReportedAboutTheTurn() {
        List<ModelCallMetadata> calls = List.of(
                new ModelCallMetadata("qwen3-8b", "chatcmpl-1", null, "stop", 1042, 259, 1301, null, null, null, null,
                        null, null, null, null, null, null, null, null, null));
        ResponseMetadata responseMetadata = ResponseMetadata.of("qwen3-8b", "chatcmpl-1", null, "stop", calls);

        when(chatMessageService.responseMetadata(eq(CHAT_ID), any())).thenReturn(responseMetadata);
        when(chatMessageService.responseMetadataCalls(eq(CHAT_ID), any())).thenReturn(calls);

        modelStreams("Hello");

        runTurn();

        assertThat(finishedMessage().getResponseMetadata()).isEqualTo(responseMetadata);
        assertThat(finishedMessage().getResponseMetadataCalls()).isEqualTo(calls);
    }

    @Test
    void normalTurnLeavesResponseMetadataNullWhenNothingWasReported() {
        modelStreams("Hello");

        runTurn();

        assertThat(finishedMessage().getResponseMetadata()).isNull();
    }

    /**
     * Cancellation mid-turn. The cancel signal is emitted once the model has already produced output,
     * which is the shape that previously raced two terminal publishers against one another. The open
     * text message is closed, and exactly one {@code CUSTOM cancel} is published — the one saying the
     * turn was cancelled, not a forwarded copy of the signal itself.
     */
    @Test
    void cancelledTurnClosesTheMessageThenAnnouncesTheCancelThenFinishes() {
        cancelAfterChunks("Partial ", "answer");

        runTurn();

        assertThat(publishedTypes()).containsExactly(
                TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END, CUSTOM, RUN_FINISHED);

        CustomEvent cancel = (CustomEvent) eventsOfType(CUSTOM).getFirst().payload();
        assertThat(cancel.name()).isEqualTo(CANCEL_ACTION);

        ChatMessage responseMessage = finishedMessage();
        assertThat(responseMessage.getMessageType()).isEqualTo(SYSTEM);
        assertThat(responseMessage.getMessage()).isEqualTo(CHAT_CANCELED);
    }

    /**
     * The duplicate-terminal regression, stated as a negative. Asserting only on the last frame would
     * let the old behaviour pass, so this inspects every published event.
     */
    @Test
    void cancelledTurnNeverPublishesAnAssistantFinish() {
        cancelAfterChunks("Partial ", "answer");

        runTurn();

        assertThat(published)
                .filteredOn(event -> RUN_FINISHED.equals(event.type()))
                .extracting(event -> ((SolesonicChatResponse) ((RunFinishedEvent) event.payload()).result())
                        .message().getMessageType())
                .containsExactly(SYSTEM);
    }

    /**
     * The cancel-path save must not run on the thread that delivered the pub/sub signal.
     */
    @Test
    void cancelledTurnSavesTheSystemMessageOffThePubSubThread() {
        AtomicReference<String> savingThread = new AtomicReference<>();

        when(chatMessageService.save(any())).thenAnswer(invocation -> {
            savingThread.set(Thread.currentThread().getName());
            return invocation.getArgument(0);
        });

        cancelAfterChunks("Partial");

        runTurn();

        verify(chatMessageService, times(1)).save(any());
        assertThat(savingThread.get()).startsWith("boundedElastic-");
    }

    /**
     * {@code RUN_ERROR} is terminal on its own in AG-UI; a {@code RUN_FINISHED} after it would be a
     * second terminal frame. The failure notification has to land <em>before</em> it: a client stops
     * reading at the terminal frame, and a resume whose cursor covers it answers {@code 204}, so a
     * frame written after {@code RUN_ERROR} is unreachable.
     */
    @Test
    void timeoutErrorPublishesTheFailureThenRunError() {
        errorTurn(new TimeoutException("too slow"));

        assertThat(publishedTypes()).containsExactly(CUSTOM, RUN_ERROR);

        CustomEvent failureEvent = (CustomEvent) eventsOfType(CUSTOM).getFirst().payload();
        assertThat(failureEvent.name()).isEqualTo(RedisStreamingChatService.FAILURE);
        assertThat(failureEvent.value()).isEqualTo(Map.of(
                "message", "The request timed out. Please try again.",
                "code", TurnErrorCode.TIMEOUT.wireValue()));

        assertThat(publishedRunError().message()).isEqualTo("The request timed out. Please try again.");
        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.TIMEOUT.wireValue());
    }

    @Test
    void unexpectedErrorPublishesTheGenericMessage() {
        errorTurn(new IllegalStateException("boom"));

        assertThat(publishedRunError().message()).isEqualTo("An unexpected error occurred. Please try again.");
        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.INTERNAL.wireValue());
    }

    @Test
    void serverErrorFromAToolCallIsClassifiedUpstreamUnavailable() {
        errorTurn(webClientResponseException(503));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.UPSTREAM_UNAVAILABLE.wireValue());
    }

    @Test
    void tooManyRequestsFromAToolCallIsClassifiedRateLimited() {
        errorTurn(webClientResponseException(429));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.RATE_LIMITED.wireValue());
    }

    @Test
    void otherClientErrorFromAToolCallIsClassifiedToolFailure() {
        errorTurn(webClientResponseException(400));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.TOOL_FAILURE.wireValue());
    }

    @Test
    void googleApiFailureIsClassifiedUpstreamUnavailableAndNeverLeaksTheRawBody() {
        errorTurn(new GoogleApiException("Google's internal error body", null));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.UPSTREAM_UNAVAILABLE.wireValue());
        assertThat(publishedRunError().message()).doesNotContain("Google's internal error body");
    }

    @Test
    void imageGenerationFailureKeepsItsOwnUserSafeMessage() {
        errorTurn(new ImageGenerationException(ImageGenerationErrorCode.BACKEND_UNAVAILABLE,
                "The image server is unavailable. Please try again."));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.TOOL_FAILURE.wireValue());
        assertThat(publishedRunError().message()).isEqualTo("The image server is unavailable. Please try again.");
    }

    @Test
    void documentReadFailureKeepsItsOwnUserSafeMessage() {
        errorTurn(new DocumentReadException("No readable text extracted from notes.pdf"));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.TOOL_FAILURE.wireValue());
        assertThat(publishedRunError().message()).isEqualTo("No readable text extracted from notes.pdf");
    }

    @Test
    void chatExceptionIsClassifiedToolFailureWithAGenericMessage() {
        errorTurn(new ChatException("No commands found for commands: [/does-not-exist]"));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.TOOL_FAILURE.wireValue());
        assertThat(publishedRunError().message()).doesNotContain("/does-not-exist");
    }

    @Test
    void unavailableAgentTellsTheUserTheChatWasReleased() {
        errorTurn(new A2AAgentUnavailableException("network"));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.UPSTREAM_UNAVAILABLE.wireValue());
        assertThat(publishedRunError().message())
                .isEqualTo("The network agent is not available right now. Send your message again to continue without it.");
    }

    /**
     * A key removed between the send and the turn: the user is told why in words they can act on,
     * never the generic failure.
     */
    @Test
    void aMissingModelKeyTellsTheUserToAskAnAdministrator() {
        errorTurn(new LlmKeyMissingException());

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.LLM_KEY_MISSING.wireValue());
        assertThat(publishedRunError().message()).isEqualTo(LlmKeyMissingException.MESSAGE);
    }

    /**
     * Pins the production failure this method exists to fix: the OpenAI Java SDK bridges its async
     * HTTP client into the reactive chain via a {@code CompletableFuture}, and a failed stage there
     * wraps the real cause in a {@link CompletionException} that Reactor's own unwrap never sees.
     * Left alone, this fell through to {@link TurnErrorCode#INTERNAL} with no actionable message.
     */
    @Test
    void contextWindowExceededWrappedInACompletionExceptionIsClassifiedWithAnActionableMessage() {
        errorTurn(new CompletionException(litellmBadRequest(
                "litellm.ContextWindowExceededError: litellm.BadRequestError: ContextWindowExceededError: "
                        + "OpenAIException - request (152057 tokens) exceeds the available context size "
                        + "(87552 tokens), try increasing it")));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.CONTEXT_LENGTH_EXCEEDED.wireValue());
        assertThat(publishedRunError().message()).isEqualTo("This conversation is too long for the model to "
                + "process. Start a new conversation, or remove an attachment or some earlier messages, "
                + "and try again.");
    }

    @Test
    void badRequestWithNoContextWindowSignalIsClassifiedValidation() {
        errorTurn(litellmBadRequest("model=auto-model does not support tool calling"));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.VALIDATION.wireValue());
    }

    @Test
    void rateLimitFromTheModelServerIsClassifiedRateLimited() {
        errorTurn(RateLimitException.builder().headers(Headers.builder().build()).build());

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.RATE_LIMITED.wireValue());
    }

    @Test
    void serverErrorFromTheModelServerIsClassifiedUpstreamUnavailable() {
        errorTurn(InternalServerException.builder().statusCode(503).headers(Headers.builder().build()).build());

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.UPSTREAM_UNAVAILABLE.wireValue());
    }

    @Test
    void ioFailureReachingTheModelServerIsClassifiedUpstreamUnavailable() {
        errorTurn(new OpenAIIoException("Connection refused"));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.UPSTREAM_UNAVAILABLE.wireValue());
    }

    private static BadRequestException litellmBadRequest(String message) {
        ErrorObject errorObject = ErrorObject.builder()
                .code((String) null)
                .message(message)
                .param((String) null)
                .type("invalid_request_error")
                .build();

        return BadRequestException.builder().headers(Headers.builder().build()).error(errorObject).build();
    }

    /**
     * An interrupted turn is a graceful stop, not a failure the user should be told about.
     */
    @Test
    void interruptedTurnPublishesNothingAndDoesNotNotify() {
        when(promptService.stream(any(), any(), any(), any()))
                .thenReturn(Flux.error(new InterruptedException("shutting down")));

        runTurn();

        assertThat(published).isEmpty();
        verify(notificationService, never()).recordFailure(any(), anyString(), any());
    }

    /**
     * Cleanup must not race the terminal frame. Closing the elicitation channel before the run has
     * finished is what the fire-and-forget publish used to allow.
     */
    @Test
    void cleanupRunsAfterTheTerminalFrameIsPublished() {
        modelStreams("Hello");

        runTurn();

        InOrder order = inOrder(redisStreamService, elicitationService, activeStreamTracker);
        order.verify(redisStreamService).publish(eq(CHAT_ID), eq(USER_ID), eq(RUN_FINISHED), any());
        order.verify(elicitationService).closeChat(CHAT_ID);
        order.verify(activeStreamTracker).remove(USER_ID, CHAT_ID);
    }

    @Test
    void aFinishedTurnIsPushedOnceItsRunFinishedFrameIsPublished() {
        modelStreams("Hello");

        runTurn();

        InOrder order = inOrder(redisStreamService, pushNotificationService);
        order.verify(redisStreamService).publish(eq(CHAT_ID), eq(USER_ID), eq(RUN_FINISHED), any());
        order.verify(pushNotificationService).push(USER_ID, new PushEvent.TurnFinished(CHAT_ID, RUN_ID));
    }

    @Test
    void aFailedTurnIsPushedOnceItsRunErrorFrameIsPublished() {
        errorTurn(new TimeoutException("too slow"));

        InOrder order = inOrder(redisStreamService, pushNotificationService);
        order.verify(redisStreamService).publish(eq(CHAT_ID), eq(USER_ID), eq(RUN_ERROR), any());
        order.verify(pushNotificationService).push(USER_ID, new PushEvent.TurnFailed(CHAT_ID, RUN_ID));
    }

    @Test
    void aCancelledTurnIsNotPushed() {
        cancelAfterChunks("Partial");

        runTurn();

        verify(pushNotificationService, never()).push(any(), any());
    }

    @Test
    void anInterruptedTurnIsNotPushed() {
        when(promptService.stream(any(), any(), any(), any()))
                .thenReturn(Flux.error(new InterruptedException("shutting down")));

        runTurn();

        verify(pushNotificationService, never()).push(any(), any());
    }

    /**
     * A push is never allowed to become the turn's failure: a throwing push after {@code RUN_FINISHED}
     * must not add a {@code RUN_ERROR} behind it.
     */
    @Test
    void aPushThatThrowsLeavesAFinishedTurnsFramesUnchanged() {
        doThrow(new IllegalStateException("push broke")).when(pushNotificationService).push(any(), any());
        modelStreams("Hello");

        runTurn();

        assertThat(publishedTypes()).containsExactly(TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END, RUN_FINISHED);
    }

    @Test
    void aPushThatThrowsLeavesAFailedTurnsFramesUnchanged() {
        doThrow(new IllegalStateException("push broke")).when(pushNotificationService).push(any(), any());

        errorTurn(new TimeoutException("too slow"));

        assertThat(publishedTypes()).containsExactly(CUSTOM, RUN_ERROR);
    }

    @Test
    void turnTracksAndUntracksTheActiveStream() {
        modelStreams("Hello");

        runTurn();

        verify(activeStreamTracker).put(USER_ID, CHAT_ID);
        verify(activeStreamTracker).remove(USER_ID, CHAT_ID);
    }

    @Test
    void anonymousTurnSkipsActiveStreamTracking() {
        modelStreams("Hello");

        StepVerifier.create(redisStreamingChatService
                        .runTurn(CHAT_ID, null, RUN_ID, new ChatRequest("hello", Set.of(), Set.of(), null), authentication))
                .verifyComplete();

        verify(activeStreamTracker, never()).put(any(), any());
        verify(activeStreamTracker, never()).remove(any(), any());
    }

    @Test
    void forwardsAnElicitationAsAToolCall() {
        UUID elicitationId = UUID.randomUUID();
        SideChannelMessage elicitation = new SideChannelMessage("elicitation",
                JSON_MAPPER.readTree("{\"message\":\"Sure?\",\"elicitationId\":\"%s\"}".formatted(elicitationId)));

        when(elicitationService.registerChat(CHAT_ID)).thenReturn(Flux.just(elicitation));
        modelStreams("Hello");

        runTurn();

        assertThat(publishedTypes()).containsSubsequence("TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END");

        ToolCallStartEvent start = (ToolCallStartEvent) eventsOfType("TOOL_CALL_START").getFirst().payload();
        assertThat(start.toolCallId()).isEqualTo(elicitationId.toString());
    }

    /**
     * The client shows the text before a prompt as its own message and opens a new bubble for the
     * answer, which {@code RUN_FINISHED} then replaces. Carrying the whole run there repeated the
     * earlier reply inside the new bubble.
     */
    @Test
    void finishedMessageIsOnlyTheTextAfterTheLatestPrompt() {
        Sinks.Many<SideChannelMessage> elicitationSink = Sinks.many().replay().all();
        SideChannelMessage elicitation = new SideChannelMessage(ELICITATION,
                JSON_MAPPER.readTree("{\"message\":\"Play or pass?\",\"elicitationId\":\"%s\"}".formatted(UUID.randomUUID())));

        when(elicitationService.registerChat(CHAT_ID)).thenReturn(elicitationSink.asFlux());
        when(promptService.stream(any(), any(), any(), any())).thenReturn(
                Flux.just("Dad, play or pass?")
                        .doOnComplete(() -> elicitationSink.tryEmitNext(elicitation))
                        .concatWith(Flux.just("Dad passes. ", "Penny has to play.")));

        runTurn();

        assertThat(eventsOfType(TEXT_MESSAGE_CONTENT))
                .extracting(event -> ((TextMessageContentEvent) event.payload()).delta())
                .containsExactly("Dad, play or pass?", "Dad passes. ", "Penny has to play.");
        assertThat(finishedMessage().getMessage()).isEqualTo("Dad passes. Penny has to play.");
    }

    @Test
    void forwardsProgressAsACustomEvent() {
        SideChannelMessage progress = new SideChannelMessage("progress", JSON_MAPPER.readTree("{\"message\":\"Searching\"}"));

        when(elicitationService.registerChat(CHAT_ID)).thenReturn(Flux.just(progress));
        modelStreams("Hello");

        runTurn();

        assertThat(eventsOfType(CUSTOM))
                .extracting(event -> ((CustomEvent) event.payload()).name())
                .containsExactly("progress");
    }

    /**
     * {@code RUN_STARTED} is the first frame of a turn and carries the persisted user message, which
     * is how a client that sent a bubble learns its real id.
     */
    @Test
    void updatePublishesRunStartedWithThePersistedUserMessage() {
        UUID userMessageId = UUID.randomUUID();
        ChatMessage userMessage = new ChatMessage();
        userMessage.setId(userMessageId);

        when(redisStreamService.getLatestOffset(CHAT_ID, USER_ID)).thenReturn(Mono.just("0"));
        when(chatMessageService.saveUserMessage(eq(CHAT_ID), eq(USER_ID), any())).thenReturn(userMessage);
        when(redisStreamService.subscribe(CHAT_ID, USER_ID, "0")).thenReturn(Flux.empty());
        when(promptService.stream(any(), any(), any(), any())).thenReturn(Flux.never());

        StepVerifier.create(redisStreamingChatService
                        .update(CHAT_ID, USER_ID, new ChatRequest("hello", Set.of(), Set.of(), null), authentication))
                .verifyComplete();

        assertThat(published.getFirst().type()).isEqualTo(RUN_STARTED);

        RunStartedEvent runStarted = (RunStartedEvent) published.getFirst().payload();
        assertThat(runStarted.threadId()).isEqualTo(CHAT_ID.toString());
        assertThat(runStarted.runId()).isNotBlank();

        RunAgentInput input = (RunAgentInput) runStarted.input();
        assertThat(input.messages()).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo(userMessageId.toString());
            assertThat(message.role()).isEqualTo(Role.USER);
            assertThat(message.content()).isEqualTo("hello");
        });
    }

    /**
     * A model picked before the chat existed must already be stored when the turn resolves which model
     * it uses, or the first turn would silently run on the default.
     */
    @Test
    void createStoresTheModelBeforeTheTurnStarts() {
        stubNewChat();
        when(chatModelSelection.set(CHAT_ID, "openai/gpt-4o")).thenReturn(Mono.empty());

        StepVerifier.create(redisStreamingChatService.create(USER_ID,
                        new ChatRequest("hello", Set.of(), Set.of(), "openai/gpt-4o"), "openai/gpt-4o", authentication))
                .verifyComplete();

        InOrder turnOrder = inOrder(chatModelSelection, chatMessageService);
        turnOrder.verify(chatModelSelection).set(CHAT_ID, "openai/gpt-4o");
        turnOrder.verify(chatMessageService).saveUserMessage(eq(CHAT_ID), eq(USER_ID), any());
    }

    @Test
    void createStoresNothingForTheDefault() {
        stubNewChat();

        StepVerifier.create(redisStreamingChatService.create(USER_ID,
                        new ChatRequest("hello", Set.of(), Set.of(), null), null, authentication))
                .verifyComplete();

        verify(chatModelSelection, never()).set(any(), anyString());
        verify(chatMessageService).saveUserMessage(eq(CHAT_ID), eq(USER_ID), any());
    }

    /**
     * Storing the selection costs the chat its choice of model, never its first turn.
     */
    @Test
    void createStillStartsTheTurnWhenTheModelCannotBeStored() {
        stubNewChat();
        when(chatModelSelection.set(CHAT_ID, "openai/gpt-4o")).thenReturn(Mono.error(new IllegalStateException("redis down")));

        StepVerifier.create(redisStreamingChatService.create(USER_ID,
                        new ChatRequest("hello", Set.of(), Set.of(), "openai/gpt-4o"), "openai/gpt-4o", authentication))
                .verifyComplete();

        verify(chatMessageService).saveUserMessage(eq(CHAT_ID), eq(USER_ID), any());
    }

    private void stubNewChat() {
        ChatMessage userMessage = new ChatMessage();
        userMessage.setId(UUID.randomUUID());

        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> {
            Chat chat = invocation.getArgument(0);
            chat.setId(CHAT_ID);

            return chat;
        });
        when(redisStreamService.getLatestOffset(CHAT_ID, USER_ID)).thenReturn(Mono.just("0"));
        when(chatMessageService.saveUserMessage(eq(CHAT_ID), eq(USER_ID), any())).thenReturn(userMessage);
        when(redisStreamService.subscribe(CHAT_ID, USER_ID, "0")).thenReturn(Flux.empty());
        when(promptService.stream(any(), any(), any(), any())).thenReturn(Flux.never());
    }

    /**
     * A non-terminal tail means a turn is still writing to this stream, so there is a live subscriber
     * on the elicitation channel for the signal to reach.
     */
    @Test
    void cancelSignalsWhenATurnIsInFlight() {
        when(redisStreamService.tail(CHAT_ID, USER_ID))
                .thenReturn(Mono.just(new RedisStreamService.StreamTail("5-0", TEXT_MESSAGE_CONTENT)));
        when(elicitationService.cancelChat(CHAT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(redisStreamingChatService.cancel(CHAT_ID, USER_ID))
                .expectNext(RedisStreamingChatService.CancelOutcome.CANCEL_REQUESTED)
                .verifyComplete();

        verify(elicitationService).cancelChat(CHAT_ID);
    }

    @Test
    void cancelIsANoOpOnceTheTurnHasFinished() {
        when(redisStreamService.tail(CHAT_ID, USER_ID))
                .thenReturn(Mono.just(new RedisStreamService.StreamTail("5-0", RUN_FINISHED)));

        StepVerifier.create(redisStreamingChatService.cancel(CHAT_ID, USER_ID))
                .expectNext(RedisStreamingChatService.CancelOutcome.NOTHING_TO_CANCEL)
                .verifyComplete();

        verify(elicitationService, never()).cancelChat(any());
    }

    @Test
    void cancelIsANoOpOnceTheTurnHasFailed() {
        when(redisStreamService.tail(CHAT_ID, USER_ID))
                .thenReturn(Mono.just(new RedisStreamService.StreamTail("5-0", RUN_ERROR)));

        StepVerifier.create(redisStreamingChatService.cancel(CHAT_ID, USER_ID))
                .expectNext(RedisStreamingChatService.CancelOutcome.NOTHING_TO_CANCEL)
                .verifyComplete();

        verify(elicitationService, never()).cancelChat(any());
    }

    @Test
    void cancelIsANoOpWhenTheChatNeverStreamed() {
        when(redisStreamService.tail(CHAT_ID, USER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(redisStreamingChatService.cancel(CHAT_ID, USER_ID))
                .expectNext(RedisStreamingChatService.CancelOutcome.NOTHING_TO_CANCEL)
                .verifyComplete();

        verify(elicitationService, never()).cancelChat(any());
    }

    /**
     * A shared session's run: the frames go to the owner's stream while the message is the sender's, and the
     * route's hooks run around the terminal frame.
     */
    private record RecordedHooks(List<String> calls, Mono<Void> beforeTerminal) implements TurnHooks {

        @Override
        public Mono<Void> beforeTerminal(UUID runId, TurnOutcome outcome) {
            return Mono.fromRunnable(() -> calls.add("before " + outcome)).then(beforeTerminal);
        }

        @Override
        public void afterTerminal(UUID runId, TurnOutcome outcome) {
            calls.add("after " + outcome);
        }

        @Override
        public void finished(UUID runId) {
            calls.add("finished");
        }
    }

    private static final UUID OWNER_ID = UUID.randomUUID();

    private TurnPlan sharedPlan(Flux<String> chunks, RecordedHooks hooks) {
        return new TurnPlan(RUN_ID, CHAT_ID, OWNER_ID, USER_ID, "Sam", new ChatRequest("towel", Set.of(), Set.of(), null),
                () -> chunks, hooks);
    }

    @Test
    void aSharedRunWritesToTheOwnersStreamAndTracksItsSender() {
        RecordedHooks hooks = new RecordedHooks(new CopyOnWriteArrayList<>(), Mono.empty());

        StepVerifier.create(redisStreamingChatService.runTurn(sharedPlan(Flux.just("Good answer!"), hooks)))
                .verifyComplete();

        verify(redisStreamService).publish(eq(CHAT_ID), eq(OWNER_ID), eq(RUN_FINISHED), any());
        verify(redisStreamService, never()).publish(eq(CHAT_ID), eq(USER_ID), anyString(), any());
        verify(activeStreamTracker).put(USER_ID, CHAT_ID);
        verify(pushNotificationService, never()).push(any(), any());
    }

    @Test
    void aRoutesHooksRunAroundTheTerminalFrame() {
        TurnHooks hooks = mock(TurnHooks.class);
        when(hooks.beforeTerminal(RUN_ID, TurnOutcome.COMPLETED)).thenReturn(Mono.empty());
        TurnPlan turnPlan = new TurnPlan(RUN_ID, CHAT_ID, OWNER_ID, USER_ID, "Sam", new ChatRequest("towel", Set.of(), Set.of(), null),
                () -> Flux.just("Good answer!"), hooks);

        StepVerifier.create(redisStreamingChatService.runTurn(turnPlan)).verifyComplete();

        //finished runs in doFinally, after the completion the verifier waits on.
        verify(hooks, timeout(1000)).finished(RUN_ID);

        InOrder order = inOrder(hooks, redisStreamService);
        order.verify(redisStreamService).publish(eq(CHAT_ID), eq(OWNER_ID), eq(TEXT_MESSAGE_END), any());
        order.verify(hooks).beforeTerminal(RUN_ID, TurnOutcome.COMPLETED);
        order.verify(redisStreamService).publish(eq(CHAT_ID), eq(OWNER_ID), eq(RUN_FINISHED), any());
        order.verify(hooks).afterTerminal(RUN_ID, TurnOutcome.COMPLETED);
        order.verify(hooks).finished(RUN_ID);
    }

    @Test
    void aHookThatFailsNeverFailsTheRun() {
        RecordedHooks hooks = new RecordedHooks(new CopyOnWriteArrayList<>(), Mono.error(new IllegalStateException("database down")));

        StepVerifier.create(redisStreamingChatService.runTurn(sharedPlan(Flux.just("Good answer!"), hooks)))
                .verifyComplete();

        assertThat(publishedTypes()).containsExactly(TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END, RUN_FINISHED);
    }

    @Test
    void aFailedRunCallsItsHooksAroundRunError() {
        List<String> calls = new CopyOnWriteArrayList<>();
        RecordedHooks hooks = new RecordedHooks(calls, Mono.empty());

        StepVerifier.create(redisStreamingChatService.runTurn(sharedPlan(Flux.error(new TimeoutException("slow")), hooks)))
                .verifyComplete();

        assertThat(publishedTypes()).containsExactly(CUSTOM, RUN_ERROR);
        assertThat(calls).containsSubsequence("before FAILED", "after FAILED");
    }

    @Test
    void startAnswersOnceRunStartedIsOutWithTheSendersName() {
        UUID userMessageId = UUID.randomUUID();
        ChatMessage userMessage = new ChatMessage();
        userMessage.setId(userMessageId);

        when(chatMessageService.saveUserMessage(eq(CHAT_ID), eq(USER_ID), any())).thenReturn(userMessage);

        StepVerifier.create(redisStreamingChatService.start(sharedPlan(Flux.never(), new RecordedHooks(new CopyOnWriteArrayList<>(), Mono.empty()))))
                .expectNext(new StartedRun(RUN_ID, userMessageId))
                .verifyComplete();

        verify(redisStreamService).publish(eq(CHAT_ID), eq(OWNER_ID), eq(RUN_STARTED), any());

        RunStartedEvent runStarted = (RunStartedEvent) published.getFirst().payload();
        RunAgentInput input = (RunAgentInput) runStarted.input();
        assertThat(input.messages()).singleElement()
                .isInstanceOfSatisfying(UserMessage.class, message -> assertThat(message.name()).isEqualTo("Sam"));
    }

    /**
     * Emits the given chunks, then fires the cancel signal, then stalls — so cancellation is what ends the
     * turn, deterministically and without a sleep.
     */
    private void cancelAfterChunks(String... chunks) {
        Sinks.Many<SideChannelMessage> elicitationSink = Sinks.many().replay().all();

        when(elicitationService.registerChat(CHAT_ID)).thenReturn(elicitationSink.asFlux());

        when(promptService.stream(any(), any(), any(), any())).thenReturn(
                Flux.just(chunks)
                        .doOnComplete(() -> elicitationSink.tryEmitNext(cancelEvent()))
                        .concatWith(Flux.never()));
    }
}
