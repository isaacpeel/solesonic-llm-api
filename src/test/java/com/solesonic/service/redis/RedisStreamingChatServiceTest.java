package com.solesonic.service.redis;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.history.ChatMessage;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.solesonic.service.chat.events.ElicitationService.CANCEL_ACTION;
import static com.solesonic.service.redis.RedisStreamingChatService.CHAT_CANCELED;
import static com.solesonic.service.redis.RedisStreamingChatService.CHUNK;
import static com.solesonic.service.redis.RedisStreamingChatService.DONE;
import static com.solesonic.service.redis.RedisStreamingChatService.ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.ai.chat.messages.MessageType.SYSTEM;

/**
 * Pins the turn lifecycle of {@link RedisStreamingChatService}: exactly one terminal frame per turn, of the
 * right kind, published before cleanup runs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisStreamingChatServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

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

    private final List<PublishedEvent> published = new ArrayList<>();

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
                generatedImageService);

        published.clear();

        when(redisStreamService.publish(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            published.add(new PublishedEvent(invocation.getArgument(2), invocation.getArgument(3)));
            return Mono.just(RecordId.of("1-0"));
        });

        when(redisStreamService.publish(any(), any(), anyString())).thenAnswer(invocation -> {
            published.add(new PublishedEvent(invocation.getArgument(2), null));
            return Mono.just(RecordId.of("1-0"));
        });

        when(activeStreamTracker.put(any(), any())).thenReturn(Mono.just(true));
        when(activeStreamTracker.remove(any(), any())).thenReturn(Mono.just(true));
        when(generatedImageService.forChatSince(any(), any())).thenReturn(List.of());
        when(chatMessageService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(elicitationService.registerChat(CHAT_ID)).thenReturn(Flux.never());
    }

    private void runTurn() {
        StepVerifier.create(redisStreamingChatService
                        .runTurn(CHAT_ID, USER_ID, new ChatRequest("hello", Set.of(), Set.of()), authentication))
                .verifyComplete();
    }

    private void modelStreams(String... chunks) {
        when(promptService.stream(any(), any(), any(), any())).thenReturn(Flux.just(chunks));
    }

    private static ServerSentEvent<?> cancelEvent() {
        return ServerSentEvent.builder(CANCEL_ACTION).event(CANCEL_ACTION).build();
    }

    private List<PublishedEvent> eventsOfType(String type) {
        return published.stream().filter(event -> type.equals(event.type())).toList();
    }

    private ChatMessage doneMessage() {
        List<PublishedEvent> doneEvents = eventsOfType(DONE);
        assertThat(doneEvents).hasSize(1);
        assertThat(doneEvents.getFirst().payload()).isInstanceOf(SolesonicChatResponse.class);
        return ((SolesonicChatResponse) doneEvents.getFirst().payload()).message();
    }

    /**
     * Case 1 — the normal path. Also the canary for a turn that never terminates: an outcome branch that
     * re-subscribes to the cancel signal hangs here instead of completing.
     */
    @Test
    void normalTurnPublishesExactlyOneAssistantDone() {
        modelStreams("Hello ", "world");

        runTurn();

        assertThat(eventsOfType(CHUNK)).hasSize(2);
        assertThat(eventsOfType(DONE)).hasSize(1);

        ChatMessage responseMessage = doneMessage();
        assertThat(responseMessage.getMessageType()).isEqualTo(ASSISTANT);
        assertThat(responseMessage.getMessage()).isEqualTo("Hello world");
        assertThat(responseMessage.getChatId()).isEqualTo(CHAT_ID);
    }

    @Test
    void normalTurnAttachesImagesGeneratedDuringTheTurn() {
        modelStreams("drawing");

        runTurn();

        verify(generatedImageService).forChatSince(eq(CHAT_ID), any());
        assertThat(doneMessage().getGeneratedImages()).isEmpty();
    }

    /**
     * Case 2 — cancellation mid-turn. The cancel signal is emitted once the model has already produced
     * output, which is the shape that previously raced two DONE publishers against one another.
     */
    @Test
    void cancelledTurnPublishesExactlyOneSystemDone() {
        cancelAfterChunks("Partial ", "answer");

        runTurn();

        assertThat(eventsOfType(DONE)).hasSize(1);

        ChatMessage responseMessage = doneMessage();
        assertThat(responseMessage.getMessageType()).isEqualTo(SYSTEM);
        assertThat(responseMessage.getMessage()).isEqualTo(CHAT_CANCELED);

        List<PublishedEvent> chunks = eventsOfType(CHUNK);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.getLast().payload())
                .isEqualTo(new RedisStreamingChatService.ChunkPayload(CHAT_CANCELED));
    }

    /**
     * Case 3 — the duplicate-DONE regression, stated as a negative. Asserting only on the last frame would
     * let the old behaviour pass, so this inspects every published event.
     */
    @Test
    void cancelledTurnNeverPublishesAnAssistantDone() {
        cancelAfterChunks("Partial ", "answer");

        runTurn();

        assertThat(published)
                .filteredOn(event -> DONE.equals(event.type()))
                .extracting(event -> ((SolesonicChatResponse) event.payload()).message().getMessageType())
                .containsExactly(SYSTEM);
    }

    /**
     * Case 4 — the cancel-path save must not run on the thread that delivered the pub/sub signal.
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

    @Test
    void timeoutErrorPublishesErrorThenDoneAndNotifies() {
        when(promptService.stream(any(), any(), any(), any()))
                .thenReturn(Flux.error(new TimeoutException("too slow")));

        runTurn();

        assertThat(published).extracting(PublishedEvent::type).containsExactly(ERROR, DONE);
        assertThat(eventsOfType(ERROR).getFirst().payload())
                .isEqualTo(new RedisStreamingChatService.ChunkPayload("The request timed out. Please try again."));

        verify(notificationService).emitFailure(CHAT_ID, "The request timed out. Please try again.");
    }

    @Test
    void unexpectedErrorPublishesTheGenericMessage() {
        when(promptService.stream(any(), any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("boom")));

        runTurn();

        assertThat(eventsOfType(ERROR).getFirst().payload())
                .isEqualTo(new RedisStreamingChatService.ChunkPayload("An unexpected error occurred. Please try again."));
    }

    /**
     * Case 6 — an interrupted turn is a graceful stop, not a failure the user should be told about.
     */
    @Test
    void interruptedTurnPublishesNothingAndDoesNotNotify() {
        when(promptService.stream(any(), any(), any(), any()))
                .thenReturn(Flux.error(new InterruptedException("shutting down")));

        runTurn();

        assertThat(published).isEmpty();
        verify(notificationService, never()).emitFailure(any(), anyString());
    }

    /**
     * Case 7 — cleanup must not race the terminal frame. Closing the elicitation channel before DONE has
     * been written is what the fire-and-forget publish used to allow.
     */
    @Test
    void cleanupRunsAfterTheDoneFrameIsPublished() {
        modelStreams("Hello");

        runTurn();

        InOrder order = inOrder(redisStreamService, elicitationService, activeStreamTracker);
        order.verify(redisStreamService).publish(eq(CHAT_ID), eq(USER_ID), eq(DONE), any());
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
                        .runTurn(CHAT_ID, null, new ChatRequest("hello", Set.of(), Set.of()), authentication))
                .verifyComplete();

        verify(activeStreamTracker, never()).put(any(), any());
        verify(activeStreamTracker, never()).remove(any(), any());
    }

    /**
     * Case 8 — the elicitation side channel forwards prompts, and does not republish the cancel signal as a
     * generic event.
     */
    @Test
    void forwardsElicitationEventsButNotTheCancelSignal() {
        ServerSentEvent<?> elicitation = ServerSentEvent.builder("ask").event("elicitation").build();

        when(elicitationService.registerChat(CHAT_ID))
                .thenReturn(Flux.just(elicitation, cancelEvent()));

        when(promptService.stream(any(), any(), any(), any())).thenReturn(Flux.just("Hello"));

        runTurn();

        assertThat(eventsOfType("elicitation")).hasSize(1);
        assertThat(eventsOfType(CANCEL_ACTION)).isEmpty();
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
