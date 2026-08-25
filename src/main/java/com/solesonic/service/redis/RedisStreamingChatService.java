package com.solesonic.service.redis;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.InitPayload;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.ollama.ChatMessageService;
import com.solesonic.service.prompt.PromptService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

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

        //Persist the user message, then start a chat stream with an init event carrying its id.
        //The save has to happen here rather than in publishToRedisStream, which runs after the
        //init event has already been published.
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
        if (userId != null) {
            //Add the users' current stream for tracking
            activeStreamTracker.put(userId, chatId)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }

        String chatModel = promptService.model(userId);
        StringBuilder assembled = new StringBuilder();

        //Marks the start of this turn, so the done payload can name the images the turn produced.
        //Time rather than message id because the assistant message is written by the chat memory
        //advisor, which does not hand its id back here.
        ZonedDateTime turnStarted = ZonedDateTime.now();

        //Set at most once, by whichever route through PromptService actually calls a chat model.
        //Read only after chunkFlow completes, which the concatWith below guarantees happens-after
        //every doOnNext that could set it.
        AtomicReference<Usage> usageRef = new AtomicReference<>();

        Flux<ServerSentEvent<?>> elicitationFlux = elicitationService.registerChat(chatId);

        Flux<ServerSentEvent<?>> cancelEvents = elicitationFlux
                .filter(sse -> CANCEL_ACTION.equalsIgnoreCase(sse.event()))
                .take(1)
                .share();

        Flux<String> chunkObjects = Flux.defer(() -> promptService.stream(chatId, userId, chatRequest, authentication, usageRef))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(StringUtils::isNotEmpty)
                .doOnNext(assembled::append);

        Flux<String> chunkFlow = chunkObjects.takeUntilOther(cancelEvents);

        Mono<Void> normalDone = Mono.<Void>fromRunnable(() -> {
            ChatMessage responseMessage = new ChatMessage();
            responseMessage.setChatId(chatId);
            responseMessage.setMessageType(ASSISTANT);
            responseMessage.setMessage(assembled.toString());
            responseMessage.setModel(chatModel);

            //References, never bytes. A client that missed the image event mid-stream — a reconnect,
            //a late subscribe — still finalises the turn with the image on it.
            responseMessage.setGeneratedImages(generatedImageService.forChatSince(chatId, turnStarted));

            ResponseMetadata responseMetadata = ResponseMetadata.of(usageRef.get(), Duration.between(turnStarted, ZonedDateTime.now()));

            SolesonicChatResponse solesonicChatResponse = new SolesonicChatResponse(chatId, responseMessage, responseMetadata);

            log.debug("Publishing done event to Redis for chat id {}", chatId);

            redisStreamService.publish(chatId, userId, DONE, solesonicChatResponse)
                    .subscribe();
        }).subscribeOn(Schedulers.boundedElastic());

        Flux<Void> cancelResponse = cancelEvents.flatMap(_ -> {
            assembled.setLength(0);
            assembled.append(CHAT_CANCELED);

            ChatMessage responseMessage = new ChatMessage();
            responseMessage.setChatId(chatId);
            responseMessage.setMessageType(SYSTEM);
            responseMessage.setMessage(assembled.toString());
            responseMessage.setModel(chatModel);
            chatMessageService.save(responseMessage);

            SolesonicChatResponse solesonicChatResponse = new SolesonicChatResponse(chatId, responseMessage);

            return redisStreamService.publish(chatId, userId, CHUNK, new ChunkPayload(CHAT_CANCELED))
                    .then(redisStreamService.publish(chatId, userId, DONE, solesonicChatResponse))
                    .then();
        });

        elicitationFlux
                .filter(serverSentEvent -> !CANCEL_ACTION.equalsIgnoreCase(serverSentEvent.event()))
                .flatMap(sse -> redisStreamService.publish(chatId, userId, sse.event(), sse.data()))
                .subscribe();

        chunkFlow.index()
                .flatMap(tuple -> redisStreamService.publish(chatId, userId, CHUNK,
                        new ChunkPayload(tuple.getT2())).then())
                .concatWith(normalDone)
                .onErrorResume(error -> {
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
                })
                .doFinally(_ -> cleanup(chatId, userId))
                .subscribe();

        cancelResponse.subscribe();
    }

    private void cleanup(UUID chatId, UUID userId) {
        log.debug("Cleaning up Redis stream for chat id: {}", chatId);

        elicitationService.closeChat(chatId);

        if (userId != null) {
            activeStreamTracker.remove(userId, chatId)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }
    }
}
