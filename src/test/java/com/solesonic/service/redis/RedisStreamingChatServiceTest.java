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
import com.solesonic.config.JacksonConfig;
import com.solesonic.exception.ChatException;
import com.solesonic.exception.google.GoogleApiException;
import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.ModelCallMetadata;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.TurnErrorCode;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.image.ImageGenerationErrorCode;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.prompt.PromptService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.solesonic.service.chat.events.ElicitationService.CANCEL_ACTION;
import static com.solesonic.service.redis.RedisStreamingChatService.CHAT_CANCELED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private Authentication authentication;

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
                new SideChannelEventTranslator(new JacksonConfig().jsonMapper()));

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
                        .runTurn(CHAT_ID, USER_ID, RUN_ID, new ChatRequest("hello", Set.of(), Set.of()), authentication))
                .verifyComplete();
    }

    private void modelStreams(String... chunks) {
        when(promptService.stream(any(), any(), any(), any())).thenReturn(Flux.just(chunks));
    }

    private static ServerSentEvent<?> cancelEvent() {
        return ServerSentEvent.builder(CANCEL_ACTION).event(CANCEL_ACTION).build();
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
                new ModelCallMetadata("qwen3-8b", "chatcmpl-1", null, "stop", 1042, 259, 1301, null, null, null,
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
    void xeroRateLimitIsClassifiedRateLimited() {
        ClientResponse response = mock(ClientResponse.class);
        when(response.statusCode()).thenReturn(HttpStatus.TOO_MANY_REQUESTS);

        errorTurn(new XeroApiException("Xero's internal error body", response));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.RATE_LIMITED.wireValue());
        assertThat(publishedRunError().message()).doesNotContain("Xero's internal error body");
    }

    @Test
    void xeroFailureWithNoResponseIsClassifiedToolFailure() {
        errorTurn(new XeroApiException("Xero returned a bulk envelope with no invoice"));

        assertThat(publishedRunError().code()).isEqualTo(TurnErrorCode.TOOL_FAILURE.wireValue());
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
                        .runTurn(CHAT_ID, null, RUN_ID, new ChatRequest("hello", Set.of(), Set.of()), authentication))
                .verifyComplete();

        verify(activeStreamTracker, never()).put(any(), any());
        verify(activeStreamTracker, never()).remove(any(), any());
    }

    @Test
    void forwardsAnElicitationAsAToolCall() {
        UUID elicitationId = UUID.randomUUID();
        ServerSentEvent<?> elicitation = ServerSentEvent
                .builder("{\"message\":\"Sure?\",\"elicitationId\":\"%s\"}".formatted(elicitationId))
                .event("elicitation")
                .build();

        when(elicitationService.registerChat(CHAT_ID)).thenReturn(Flux.just(elicitation));
        modelStreams("Hello");

        runTurn();

        assertThat(publishedTypes()).containsSubsequence("TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END");

        ToolCallStartEvent start = (ToolCallStartEvent) eventsOfType("TOOL_CALL_START").getFirst().payload();
        assertThat(start.toolCallId()).isEqualTo(elicitationId.toString());
    }

    @Test
    void forwardsProgressAsACustomEvent() {
        ServerSentEvent<?> progress = ServerSentEvent.builder("{\"message\":\"Searching\"}").event("progress").build();

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
                        .update(CHAT_ID, USER_ID, new ChatRequest("hello", Set.of(), Set.of()), authentication))
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
     * Emits the given chunks, then fires the cancel signal, then stalls — so cancellation is what ends the
     * turn, deterministically and without a sleep.
     */
    private void cancelAfterChunks(String... chunks) {
        Sinks.Many<ServerSentEvent<?>> elicitationSink = Sinks.many().replay().all();

        when(elicitationService.registerChat(CHAT_ID)).thenReturn(elicitationSink.asFlux());

        when(promptService.stream(any(), any(), any(), any())).thenReturn(
                Flux.just(chunks)
                        .doOnComplete(() -> elicitationSink.tryEmitNext(cancelEvent()))
                        .concatWith(Flux.never()));
    }
}
