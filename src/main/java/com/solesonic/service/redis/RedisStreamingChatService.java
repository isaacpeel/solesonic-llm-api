package com.solesonic.service.redis;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.CustomEvent;
import com.agui.community.core.event.Event;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.event.RunFinishedEvent;
import com.agui.community.core.event.RunStartedEvent;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageEndEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.message.Role;
import com.agui.community.core.message.UserMessage;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
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
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.redis.model.TerminalEvents;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.prompt.PromptService;
import com.solesonic.util.ResponseSanitizer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.solesonic.service.chat.events.ElicitationService.CANCEL_ACTION;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.ai.chat.messages.MessageType.SYSTEM;

/**
 * Runs a chat turn and writes it to the durable Redis stream as AG-UI events.
 * <p>
 * A turn is one AG-UI run: {@code RUN_STARTED}, the assistant's text as one
 * {@code TEXT_MESSAGE_START}/{@code CONTENT}/{@code END} message, then exactly one terminal frame —
 * {@code RUN_FINISHED}, or {@code RUN_ERROR} alone. An elicitation arrives mid-run as a tool call and
 * is answered out of band without ending the run: unlike AG-UI's interrupt model, the connection and
 * the parked MCP tool call both stay open across it.
 */
@Service
public class RedisStreamingChatService {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamingChatService.class);
    public static final String CHAT_CANCELED = "Chat canceled.";
    public static final String FAILURE = "failure";
    private static final String MESSAGE = "message";

    private static final String TIMEOUT_MESSAGE = "The request timed out. Please try again.";
    private static final String UPSTREAM_UNAVAILABLE_MESSAGE = "An upstream service is temporarily unavailable. Please try again.";
    private static final String RATE_LIMITED_MESSAGE = "An upstream service is rate limiting requests. Please try again shortly.";
    private static final String TOOL_FAILURE_MESSAGE = "A tool call failed while answering. Please try again.";
    private static final String CONTEXT_LENGTH_EXCEEDED_MESSAGE = "This conversation is too long for the model to "
            + "process. Start a new conversation, or remove an attachment or some earlier messages, and try again.";
    private static final String INTERNAL_MESSAGE = "An unexpected error occurred. Please try again.";

    public enum CancelOutcome {
        CANCEL_REQUESTED,
        NOTHING_TO_CANCEL
    }

    private final ChatRepository chatRepository;
    private final PromptService promptService;
    private final ElicitationService elicitationService;
    private final ChatMessageService chatMessageService;
    private final RedisStreamService redisStreamService;
    private final ActiveStreamTracker activeStreamTracker;
    private final NotificationService notificationService;
    private final GeneratedImageService generatedImageService;
    private final SideChannelEventTranslator sideChannelEventTranslator;

    public RedisStreamingChatService(ChatRepository chatRepository,
                                     PromptService promptService,
                                     ElicitationService elicitationService,
                                     ChatMessageService chatMessageService,
                                     RedisStreamService redisStreamService,
                                     ActiveStreamTracker activeStreamTracker,
                                     NotificationService notificationService,
                                     GeneratedImageService generatedImageService,
                                     SideChannelEventTranslator sideChannelEventTranslator) {
        this.chatRepository = chatRepository;
        this.promptService = promptService;
        this.elicitationService = elicitationService;
        this.chatMessageService = chatMessageService;
        this.redisStreamService = redisStreamService;
        this.activeStreamTracker = activeStreamTracker;
        this.notificationService = notificationService;
        this.generatedImageService = generatedImageService;
        this.sideChannelEventTranslator = sideChannelEventTranslator;
    }

    private Chat save(Chat chat) {
        chat.setTimestamp(ZonedDateTime.now());
        return chatRepository.save(chat);
    }

    public Flux<ServerSentEvent<?>> create(UUID userId,
                                           ChatRequest chatRequest,
                                           Authentication authentication) {

        Chat chat = new Chat();
        chat.setUserId(userId);
        chat = save(chat);

        UUID chatId = chat.getId();

        log.debug("Starting Redis streaming chat with new chat id {}", chatId);

        return update(chatId, userId, chatRequest, authentication);
    }

    /**
     * Starts a turn and returns a view of it.
     * <p>
     * Resuming an existing turn is deliberately not this method's job — see
     * {@link StreamResumeService}. A turn runs to completion whether or not anyone is listening,
     * so replaying one must never re-enter this path.
     * <p>
     * {@code RUN_STARTED} carries the persisted user message in its {@code input}, which is how a
     * client learns the real id of the bubble it just sent.
     */
    public Flux<ServerSentEvent<?>> update(UUID chatId,
                                           UUID userId,
                                           ChatRequest chatRequest,
                                           Authentication authentication) {

        UUID runId = UUID.randomUUID();

        return redisStreamService.getLatestOffset(chatId, userId)
                .flatMap(offset -> Mono
                        .fromCallable(() -> chatMessageService.saveUserMessage(chatId, userId, chatRequest))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(chatMessage -> new StreamStart(offset, chatMessage)))
                .flatMap(streamStart -> publish(chatId, userId,
                        runStarted(chatId, runId, streamStart.chatMessage(), chatRequest))
                        .thenReturn(streamStart))
                .flatMapMany(streamStart -> {
                    publishToRedisStream(chatId, userId, runId, chatRequest, authentication);
                    return redisStreamService.subscribe(chatId, userId, streamStart.offset());
                });
    }

    private record StreamStart(String offset, ChatMessage chatMessage) {
    }

    private static RunStartedEvent runStarted(UUID chatId, UUID runId, ChatMessage userMessage, ChatRequest chatRequest) {
        RunAgentInput input = new RunAgentInput(chatId.toString(), runId.toString(),
                List.of(new UserMessage(String.valueOf(userMessage.getId()), chatRequest.chatMessage())),
                List.of());

        return new RunStartedEvent(chatId.toString(), runId.toString(), null, input, null, null);
    }

    /**
     * Stops a turn in flight. A terminal tail means the turn already finished — nothing to signal —
     * and an empty tail means the chat never streamed at all; either way there is no live subscriber
     * on the elicitation channel to receive a cancel, so this checks first rather than firing blind.
     */
    public Mono<CancelOutcome> cancel(UUID chatId, UUID userId) {
        return redisStreamService.tail(chatId, userId)
                .flatMap(tail -> TerminalEvents.isTerminal(tail.type())
                        ? Mono.just(CancelOutcome.NOTHING_TO_CANCEL)
                        : elicitationService.cancelChat(chatId).thenReturn(CancelOutcome.CANCEL_REQUESTED))
                .defaultIfEmpty(CancelOutcome.NOTHING_TO_CANCEL);
    }

    private void publishToRedisStream(UUID chatId,
                                      UUID userId,
                                      UUID runId,
                                      ChatRequest chatRequest,
                                      Authentication authentication) {
        runTurn(chatId, userId, runId, chatRequest, authentication).subscribe();
    }

    /**
     * The whole turn as one cold {@link Mono}, so a test can drive it to completion. Production subscribes
     * and walks away — a turn is not driven by the HTTP response.
     */
    Mono<Void> runTurn(UUID chatId,
                       UUID userId,
                       UUID runId,
                       ChatRequest chatRequest,
                       Authentication authentication) {
        trackActiveStream(userId, chatId);

        //Marks the start of this turn, so the finished payload can name the images the turn produced.
        //Time rather than message id because the assistant message is written by the chat memory
        //advisor, which does not hand its id back here.
        ZonedDateTime turnStarted = ZonedDateTime.now();

        Flux<ServerSentEvent<?>> elicitationEvents = elicitationService.registerChat(chatId);

        //Exactly one consumer, inside streamTurn. Subscribing a second time would reconnect to the
        //underlying pub/sub channel, which replays nothing already delivered.
        Flux<ServerSentEvent<?>> cancelEvents = elicitationEvents
                .filter(serverSentEvent -> CANCEL_ACTION.equalsIgnoreCase(serverSentEvent.event()))
                .take(1);

        forwardElicitationEvents(chatId, userId, elicitationEvents);

        Turn turn = new Turn(chatId, userId, runId, UUID.randomUUID().toString(), turnStarted);

        return streamTurn(turn, chatRequest, authentication, cancelEvents)
                .onErrorResume(error -> handleStreamError(chatId, userId, error))
                .doFinally(_ -> cleanup(chatId, userId));
    }

    /**
     * The identifiers one turn's frames carry. {@code messageId} is a wire id for the assistant's
     * text message only: the persisted assistant row is written by the chat memory advisor, which
     * assigns its own id and never hands it back here.
     */
    private record Turn(UUID chatId, UUID userId, UUID runId, String messageId, ZonedDateTime started) {
    }

    /**
     * Runs one turn: stream text until the model stops or the user cancels, then publish exactly one
     * terminal frame saying which of the two happened.
     */
    private Mono<Void> streamTurn(Turn turn,
                                  ChatRequest chatRequest,
                                  Authentication authentication,
                                  Flux<ServerSentEvent<?>> cancelEvents) {

        StringBuilder assembled = new StringBuilder();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean messageStarted = new AtomicBoolean();

        Flux<String> chunkFlow = Flux.defer(() -> promptService.stream(turn.chatId(), turn.userId(), chatRequest, authentication))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(StringUtils::isNotEmpty)
                .transform(ResponseSanitizer.sanitize())
                .doOnNext(assembled::append)
                .takeUntilOther(cancelEvents.doOnNext(_ -> cancelled.set(true)));

        //concatMap, not flatMap: a text message's frames are only meaningful in order, and the start
        //frame has to land before the first delta that refers to it.
        Mono<Void> publishChunks = chunkFlow
                .concatMap(chunk -> startMessageOnce(turn, messageStarted)
                        .then(publish(turn, new TextMessageContentEvent(turn.messageId(), chunk, null, null))))
                .then();

        return publishChunks
                .then(Mono.defer(() -> endMessageIfStarted(turn, messageStarted)))
                .then(Mono.defer(() -> cancelled.get()
                        ? publishCancelledOutcome(turn)
                        : publishCompletedOutcome(turn, assembled.toString())));
    }

    private Mono<Void> startMessageOnce(Turn turn, AtomicBoolean messageStarted) {
        if (!messageStarted.compareAndSet(false, true)) {
            return Mono.empty();
        }

        return publish(turn, new TextMessageStartEvent(turn.messageId(), Role.ASSISTANT, null, null)).then();
    }

    private Mono<Void> endMessageIfStarted(Turn turn, AtomicBoolean messageStarted) {
        if (!messageStarted.get()) {
            return Mono.empty();
        }

        return publish(turn, new TextMessageEndEvent(turn.messageId(), null, null)).then();
    }

    private Mono<Void> publishCompletedOutcome(Turn turn, String content) {
        UUID chatId = turn.chatId();

        return Mono.fromCallable(() -> new CompletedTurn(
                        generatedImageService.forChatSince(chatId, turn.started()),
                        chatMessageService.responseMetadata(chatId, turn.started()),
                        chatMessageService.responseMetadataCalls(chatId, turn.started())))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(completedTurn -> {
                    ChatMessage responseMessage = new ChatMessage();
                    responseMessage.setChatId(chatId);
                    responseMessage.setMessageType(ASSISTANT);
                    responseMessage.setMessage(content);

                    //References, never bytes. A client that missed the image event mid-stream — a reconnect,
                    //a late subscribe — still finalises the turn with the image on it.
                    responseMessage.setGeneratedImages(completedTurn.generatedImages());

                    //Read back rather than carried down from the model call: the row is written by the
                    //chat memory advisor mid-turn and the accounting is attached to it once the stream
                    //completes, both of which are behind us by here. Null for a turn no chat model
                    //answered.
                    responseMessage.setResponseMetadata(completedTurn.responseMetadata());
                    responseMessage.setResponseMetadataCalls(completedTurn.responseMetadataCalls());

                    log.debug("Publishing run finished event to Redis for chat id {}", chatId);

                    return publish(turn, runFinished(turn, responseMessage));
                })
                .then();
    }

    /**
     * The three blocking reads the finished frame needs, fetched together so the turn pays one hop
     * onto {@code boundedElastic} rather than three.
     */
    private record CompletedTurn(List<GeneratedImageSummary> generatedImages,
                                 ResponseMetadata responseMetadata,
                                 List<ModelCallMetadata> responseMetadataCalls) {
    }

    /**
     * A cancelled run still finishes rather than erroring — the user asked for it — and says so with a
     * {@code CUSTOM cancel} frame ahead of {@code RUN_FINISHED}, whose result is the {@code SYSTEM}
     * message recording the cancellation.
     */
    private Mono<Void> publishCancelledOutcome(Turn turn) {
        return Mono.fromCallable(() -> {
                    ChatMessage responseMessage = new ChatMessage();
                    responseMessage.setChatId(turn.chatId());
                    responseMessage.setMessageType(SYSTEM);
                    responseMessage.setMessage(CHAT_CANCELED);

                    return chatMessageService.save(responseMessage);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(responseMessage -> publish(turn,
                        new CustomEvent(CANCEL_ACTION, Map.of(MESSAGE, CHAT_CANCELED), null, null))
                        .then(Mono.defer(() -> publish(turn, runFinished(turn, responseMessage)))))
                .then();
    }

    private static RunFinishedEvent runFinished(Turn turn, ChatMessage responseMessage) {
        return new RunFinishedEvent(turn.chatId().toString(), turn.runId().toString(), null,
                new SolesonicChatResponse(turn.chatId(), responseMessage), null, null);
    }

    private Mono<Void> handleStreamError(UUID chatId, UUID userId, Throwable error) {
        Throwable unwrapped = unwrapAsync(error);

        if (Exceptions.isCancel(unwrapped) || unwrapped instanceof InterruptedException) {
            log.info("Redis stream cancelled gracefully for chat id {}", chatId);
            return Mono.empty();
        }

        log.error("Redis stream error for chat id {}", chatId, error);

        TurnErrorCode code = classify(unwrapped);
        String userMessage = userMessage(unwrapped, code);

        RunErrorEvent runError = new RunErrorEvent(userMessage, code.wireValue(), null, null);

        //Written straight to the stream, ahead of RUN_ERROR, rather than through the pub/sub side
        //channel: that path is a hop longer, and a frame landing after the terminal one is never read.
        return Mono.fromCallable(() -> notificationService.recordFailure(chatId, userMessage, code))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(failure -> publish(chatId, userId, new CustomEvent(FAILURE, failure, null, null)))
                .then(Mono.defer(() -> publish(chatId, userId, runError)))
                .then();
    }

    /**
     * Peels off the JDK's own async-composition wrappers ({@link CompletionException},
     * {@link ExecutionException}) around a turn failure, in addition to Reactor's
     * {@link Exceptions#unwrap(Throwable)}. The OpenAI Java SDK bridges its
     * {@code CompletableFuture}-based async HTTP client into the reactive chain
     * ({@code OpenAiChatModel.stream}), and a stage failing inside that chain wraps the real
     * cause ({@code com.openai.errors.BadRequestException}, etc.) in a {@code CompletionException}
     * that Reactor never sees and therefore never unwraps — left alone, every such failure falls
     * through {@link #classify(Throwable)} to {@link TurnErrorCode#INTERNAL}.
     */
    private static Throwable unwrapAsync(Throwable throwable) {
        Throwable current = Exceptions.unwrap(throwable);

        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = Exceptions.unwrap(current.getCause());
        }

        return current;
    }

    /**
     * Sorts an unwrapped turn failure onto the closed set of codes the client renders copy for.
     * <p>
     * Most branches mirror how the same exception type is already classified where it is thrown
     * from a synchronous REST endpoint ({@code GoogleExceptionHandler}, the Jira/Xero handlers) —
     * the streaming path just has nowhere to route to but this one method, since
     * {@code @ControllerAdvice} never sees an exception a fire-and-forget {@code .subscribe()}'d
     * turn throws. {@link XeroApiException} is the one deliberate exception: its REST handler
     * always answers {@code UPSTREAM_UNAVAILABLE}, but here a non-429 defaults to
     * {@link TurnErrorCode#TOOL_FAILURE} instead — {@code docs/api.md} gives a client identical
     * guidance ("retry") for both, and {@code TOOL_FAILURE}'s own contract already names Xero as
     * an example.
     */
    private static TurnErrorCode classify(Throwable unwrapped) {
        if (unwrapped instanceof TimeoutException) {
            return TurnErrorCode.TIMEOUT;
        }

        if (unwrapped instanceof WebClientResponseException webClientResponseException) {
            return classifyStatus(webClientResponseException.getStatusCode());
        }

        if (unwrapped instanceof GoogleApiException) {
            return TurnErrorCode.UPSTREAM_UNAVAILABLE;
        }

        if (unwrapped instanceof XeroApiException xeroApiException) {
            ClientResponse response = xeroApiException.getResponse();

            if (response != null && classifyStatus(response.statusCode()) == TurnErrorCode.RATE_LIMITED) {
                return TurnErrorCode.RATE_LIMITED;
            }

            return TurnErrorCode.TOOL_FAILURE;
        }

        if (unwrapped instanceof ImageGenerationException || unwrapped instanceof DocumentReadException
                || unwrapped instanceof ChatException) {
            return TurnErrorCode.TOOL_FAILURE;
        }

        if (unwrapped instanceof OpenAIIoException) {
            return TurnErrorCode.UPSTREAM_UNAVAILABLE;
        }

        if (unwrapped instanceof OpenAIServiceException openAiServiceException) {
            return classifyOpenAiFailure(openAiServiceException);
        }

        return TurnErrorCode.INTERNAL;
    }

    private static TurnErrorCode classifyStatus(HttpStatusCode statusCode) {
        if (statusCode.value() == 429) {
            return TurnErrorCode.RATE_LIMITED;
        }

        if (statusCode.is5xxServerError()) {
            return TurnErrorCode.UPSTREAM_UNAVAILABLE;
        }

        return TurnErrorCode.TOOL_FAILURE;
    }

    /**
     * Classifies a failure the model server (or the LiteLLM proxy in front of it) returned for the
     * chat completion request itself — not a tool call, so {@link #classifyStatus(HttpStatusCode)}'s
     * {@link TurnErrorCode#TOOL_FAILURE} default does not apply here; an otherwise-unclassified 4xx
     * is {@link TurnErrorCode#VALIDATION} instead, since it is the request that was rejected, before
     * generation started.
     */
    private static TurnErrorCode classifyOpenAiFailure(OpenAIServiceException openAiServiceException) {
        if (isContextLengthExceeded(openAiServiceException)) {
            return TurnErrorCode.CONTEXT_LENGTH_EXCEEDED;
        }

        if (openAiServiceException.statusCode() == 429) {
            return TurnErrorCode.RATE_LIMITED;
        }

        if (openAiServiceException.statusCode() >= 500) {
            return TurnErrorCode.UPSTREAM_UNAVAILABLE;
        }

        return TurnErrorCode.VALIDATION;
    }

    /**
     * LiteLLM reports a context-window overflow as a 400 whose message embeds
     * {@code litellm.ContextWindowExceededError} — there is no dedicated status code or
     * {@code OpenAIServiceException} subtype for it, so the message is the only signal available.
     */
    private static boolean isContextLengthExceeded(OpenAIServiceException openAiServiceException) {
        String message = openAiServiceException.getMessage();

        return message != null
                && (Strings.CI.contains(message, "ContextWindowExceededError")
                        || Strings.CI.contains(message, "context_length_exceeded")
                        || Strings.CI.contains(message, "maximum context length"));
    }

    /**
     * The text a client sees. Only {@link ImageGenerationException} and {@link DocumentReadException}
     * carry a message already vetted as user-safe at every current throw site — everything else gets
     * a generic per-code sentence, because {@link ChatException}, {@link GoogleApiException} and
     * {@link XeroApiException} can and do wrap an upstream's own wording.
     */
    private static String userMessage(Throwable unwrapped, TurnErrorCode code) {
        if (unwrapped instanceof ImageGenerationException || unwrapped instanceof DocumentReadException) {
            return unwrapped.getMessage();
        }

        return switch (code) {
            case TIMEOUT -> TIMEOUT_MESSAGE;
            case UPSTREAM_UNAVAILABLE -> UPSTREAM_UNAVAILABLE_MESSAGE;
            case RATE_LIMITED -> RATE_LIMITED_MESSAGE;
            case TOOL_FAILURE -> TOOL_FAILURE_MESSAGE;
            case CONTEXT_LENGTH_EXCEEDED -> CONTEXT_LENGTH_EXCEEDED_MESSAGE;
            case RECONNECT_REQUIRED, VALIDATION, STREAM_UNAVAILABLE, INTERNAL -> INTERNAL_MESSAGE;
        };
    }

    private void forwardElicitationEvents(UUID chatId, UUID userId, Flux<ServerSentEvent<?>> elicitationEvents) {
        elicitationEvents
                .filter(serverSentEvent -> !CANCEL_ACTION.equalsIgnoreCase(serverSentEvent.event()))
                .concatMap(serverSentEvent -> Flux.fromIterable(sideChannelEventTranslator.translate(serverSentEvent))
                        .concatMap(event -> publish(chatId, userId, event)))
                .subscribe();
    }

    private Mono<RecordId> publish(Turn turn, Event event) {
        return publish(turn.chatId(), turn.userId(), event);
    }

    private Mono<RecordId> publish(UUID chatId, UUID userId, Event event) {
        return redisStreamService.publish(chatId, userId, event.type().value(), event);
    }

    private void trackActiveStream(UUID userId, UUID chatId) {
        if (userId == null) {
            return;
        }

        activeStreamTracker.put(userId, chatId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private void untrackActiveStream(UUID userId, UUID chatId) {
        if (userId == null) {
            return;
        }

        activeStreamTracker.remove(userId, chatId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private void cleanup(UUID chatId, UUID userId) {
        log.debug("Cleaning up Redis stream for chat id: {}", chatId);

        elicitationService.closeChat(chatId);
        untrackActiveStream(userId, chatId);
    }
}
