package com.solesonic.service.redis;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.InitPayload;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.prompt.PromptService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.solesonic.service.chat.events.ElicitationService.CANCEL_ACTION;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.ai.chat.messages.MessageType.SYSTEM;

@Service
public class RedisStreamingChatService {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamingChatService.class);
    public static final String CHUNK = "chunk";
    public static final String INIT = "init";
    public static final String DONE = "done";
    public static final String ERROR = "error";
    public static final String CHAT_CANCELED = "Chat canceled.";

    public record ChunkPayload(String content) {
    }

    private final ChatRepository chatRepository;
    private final PromptService promptService;
    private final ElicitationService elicitationService;
    private final ChatMessageService chatMessageService;
    private final RedisStreamService redisStreamService;
    private final ActiveStreamTracker activeStreamTracker;
    private final NotificationService notificationService;
    private final GeneratedImageService generatedImageService;

    public RedisStreamingChatService(ChatRepository chatRepository,
                                     PromptService promptService,
                                     ElicitationService elicitationService,
                                     ChatMessageService chatMessageService,
                                     RedisStreamService redisStreamService,
                                     ActiveStreamTracker activeStreamTracker,
                                     NotificationService notificationService,
                                     GeneratedImageService generatedImageService) {
        this.chatRepository = chatRepository;
        this.promptService = promptService;
        this.elicitationService = elicitationService;
        this.chatMessageService = chatMessageService;
        this.redisStreamService = redisStreamService;
        this.activeStreamTracker = activeStreamTracker;
        this.notificationService = notificationService;
        this.generatedImageService = generatedImageService;
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
     */
    public Flux<ServerSentEvent<?>> update(UUID chatId,
                                           UUID userId,
                                           ChatRequest chatRequest,
                                           Authentication authentication) {

        return redisStreamService.getLatestOffset(chatId, userId)
                .flatMap(offset -> Mono
                        .fromCallable(() -> chatMessageService.saveUserMessage(chatId, userId, chatRequest))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(chatMessage -> new StreamStart(offset, chatMessage)))
                .flatMap(streamStart -> redisStreamService
                        .publish(chatId, userId, INIT, new InitPayload(chatId, streamStart.chatMessage().getId()))
                        .thenReturn(streamStart))
                .flatMapMany(streamStart -> {
                    publishToRedisStream(chatId, userId, chatRequest, authentication);
                    return redisStreamService.subscribe(chatId, userId, streamStart.offset());
                });
    }

    private record StreamStart(String offset, ChatMessage chatMessage) {
    }

    private void publishToRedisStream(UUID chatId,
                                      UUID userId,
                                      ChatRequest chatRequest,
                                      Authentication authentication) {
        runTurn(chatId, userId, chatRequest, authentication).subscribe();
    }

    /**
     * The whole turn as one cold {@link Mono}, so a test can drive it to completion. Production subscribes
     * and walks away — a turn is not driven by the HTTP response.
     */
    Mono<Void> runTurn(UUID chatId,
                       UUID userId,
                       ChatRequest chatRequest,
                       Authentication authentication) {
        trackActiveStream(userId, chatId);

        //Marks the start of this turn, so the done payload can name the images the turn produced.
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

        return streamTurn(chatId, userId, chatRequest, authentication, cancelEvents, turnStarted)
                .onErrorResume(error -> handleStreamError(chatId, userId, error))
                .doFinally(_ -> cleanup(chatId, userId));
    }

    /**
     * Runs one turn: stream chunks until the model stops or the user cancels, then publish exactly one
     * terminal frame saying which of the two happened.
     */
    private Mono<Void> streamTurn(UUID chatId,
                                  UUID userId,
                                  ChatRequest chatRequest,
                                  Authentication authentication,
                                  Flux<ServerSentEvent<?>> cancelEvents,
                                  ZonedDateTime turnStarted) {

        StringBuilder assembled = new StringBuilder();
        AtomicBoolean cancelled = new AtomicBoolean();

        Flux<String> chunkFlow = Flux.defer(() -> promptService.stream(chatId, userId, chatRequest, authentication))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(StringUtils::isNotEmpty)
                .doOnNext(assembled::append)
                .takeUntilOther(cancelEvents.doOnNext(_ -> cancelled.set(true)));

        Mono<Void> publishChunks = chunkFlow
                .flatMap(chunk -> redisStreamService.publish(chatId, userId, CHUNK, new ChunkPayload(chunk)))
                .then();

        return publishChunks.then(Mono.defer(() -> cancelled.get()
                ? publishCancelledOutcome(chatId, userId)
                : publishCompletedOutcome(chatId, userId, turnStarted, assembled.toString())));
    }

    private Mono<Void> publishCompletedOutcome(UUID chatId,
                                               UUID userId,
                                               ZonedDateTime turnStarted,
                                               String content) {
        return Mono.fromCallable(() -> generatedImageService.forChatSince(chatId, turnStarted))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(generatedImages -> {
                    ChatMessage responseMessage = new ChatMessage();
                    responseMessage.setChatId(chatId);
                    responseMessage.setMessageType(ASSISTANT);
                    responseMessage.setMessage(content);

                    //References, never bytes. A client that missed the image event mid-stream — a reconnect,
                    //a late subscribe — still finalises the turn with the image on it.
                    responseMessage.setGeneratedImages(generatedImages);

                    log.debug("Publishing done event to Redis for chat id {}", chatId);

                    return redisStreamService.publish(chatId, userId, DONE,
                            new SolesonicChatResponse(chatId, responseMessage));
                })
                .then();
    }

    private Mono<Void> publishCancelledOutcome(UUID chatId, UUID userId) {
        return Mono.fromCallable(() -> {
                    ChatMessage responseMessage = new ChatMessage();
                    responseMessage.setChatId(chatId);
                    responseMessage.setMessageType(SYSTEM);
                    responseMessage.setMessage(CHAT_CANCELED);

                    return chatMessageService.save(responseMessage);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(responseMessage -> redisStreamService
                        .publish(chatId, userId, CHUNK, new ChunkPayload(CHAT_CANCELED))
                        .then(redisStreamService.publish(chatId, userId, DONE,
                                new SolesonicChatResponse(chatId, responseMessage))))
                .then();
    }

    private Mono<Void> handleStreamError(UUID chatId, UUID userId, Throwable error) {
        Throwable unwrapped = Exceptions.unwrap(error);

        if (Exceptions.isCancel(unwrapped) || unwrapped instanceof InterruptedException) {
            log.info("Redis stream cancelled gracefully for chat id {}", chatId);
            return Mono.empty();
        }

        log.error("Redis stream error for chat id {}", chatId, error);

        String userMessage = (unwrapped instanceof TimeoutException)
                ? "The request timed out. Please try again."
                : "An unexpected error occurred. Please try again.";

        notificationService.emitFailure(chatId, userMessage);

        return redisStreamService.publish(chatId, userId, ERROR, new ChunkPayload(userMessage))
                .then(redisStreamService.publish(chatId, userId, DONE))
                .then();
    }

    private void forwardElicitationEvents(UUID chatId, UUID userId, Flux<ServerSentEvent<?>> elicitationEvents) {
        elicitationEvents
                .filter(serverSentEvent -> !CANCEL_ACTION.equalsIgnoreCase(serverSentEvent.event()))
                .flatMap(serverSentEvent -> redisStreamService.publish(chatId, userId,
                        serverSentEvent.event(), serverSentEvent.data()))
                .subscribe();
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
