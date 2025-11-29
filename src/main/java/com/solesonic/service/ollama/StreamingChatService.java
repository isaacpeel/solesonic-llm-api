package com.solesonic.service.ollama;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.chat.ElicitationService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.solesonic.service.chat.ElicitationService.CANCEL_ACTION;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.ai.chat.messages.MessageType.SYSTEM;

@Service
public class StreamingChatService {
    private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);
    public static final String CHUNK = "chunk";
    public static final String INIT = "init";
    public static final String DONE = "done";

    public static final String CHAT_CANCELED = "Chat canceled.";

    private final ChatRepository chatRepository;
    private final PromptService promptService;
    private final ElicitationService elicitationService;
    private final UserRequestContext userRequestContext;
    private final ChatMessageService chatMessageService;

    private final ConcurrentHashMap<UUID, Sinks.Many<ServerSentEvent<?>>> streamCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> userActiveStreams = new ConcurrentHashMap<>();

    public StreamingChatService(ChatRepository chatRepository,
                                PromptService promptService,
                                ElicitationService elicitationService,
                                UserRequestContext userRequestContext, ChatMessageService chatMessageService) {
        this.chatRepository = chatRepository;
        this.promptService = promptService;
        this.elicitationService = elicitationService;
        this.userRequestContext = userRequestContext;
        this.chatMessageService = chatMessageService;
    }

    private Chat save(Chat chat) {
        chat.setTimestamp(ZonedDateTime.now());
        return chatRepository.save(chat);
    }

    public Flux<ServerSentEvent<?>> create(UUID userId,
                                           ChatRequest chatRequest,
                                           String lastEventId) {

        if (StringUtils.isNotBlank(lastEventId)) {
            UUID activeChatId = userActiveStreams.get(userId);

            // If we found an active mapping and the stream is still alive in the cache
            if (activeChatId != null && streamCache.containsKey(activeChatId)) {
                log.info("Auto-resuming active chat {} for user {} based on Last-Event-ID", activeChatId, userId);
                return update(activeChatId, userId, chatRequest, lastEventId);
            } else {
                log.debug("Last-Event-ID present but no active stream found for user {}. Starting new session.", userId);
            }
        }

        Chat chat = new Chat();
        chat.setUserId(userId);
        chat = save(chat);
        UUID chatId = chat.getId();

        log.debug("Starting streaming chat with new chat id {}", chatId);

        return update(chatId, userId, chatRequest, null);
    }

    public record ChunkPayload(String content) {}

    public Flux<ServerSentEvent<?>> update(UUID chatId, UUID userId, ChatRequest chatRequest, String lastEventId) {
        userRequestContext.setChatId(chatId);

        Long resumeFromIndex = null;

        if (StringUtils.isNotBlank(lastEventId)) {
            try {
                resumeFromIndex = Long.parseLong(lastEventId);
                log.debug("Resuming stream from index {} for chat id {}", resumeFromIndex, chatId);
            } catch (NumberFormatException exception) {
                log.warn("Invalid Last-Event-ID '{}' provided for chat id {}", lastEventId, chatId);
            }
        }

        Long finalResumeFromIndex = resumeFromIndex;

        Sinks.Many<ServerSentEvent<?>> sink = streamCache.computeIfAbsent(chatId, id -> {
            log.info("Initializing new background stream for chat id {}", id);
            return initializeStream(id, userId, chatRequest);
        });

        return sink.asFlux()
                .filter(sse -> {
                    if (finalResumeFromIndex == null) return true;
                    if (sse.id() == null) return true;
                    try {
                        return Long.parseLong(sse.id()) > finalResumeFromIndex;
                    } catch (NumberFormatException e) {
                        return true;
                    }
                });
    }

    private Sinks.Many<ServerSentEvent<?>> initializeStream(UUID chatId, UUID userId, ChatRequest chatRequest) {
        if (userId != null) {
            userActiveStreams.put(userId, chatId);
        }

        Sinks.Many<ServerSentEvent<?>> sink = Sinks.many().replay().all();

        String chatMessage = chatRequest.chatMessage();
        String chatModel = promptService.model(userId);
        StringBuilder assembled = new StringBuilder();

        Flux<ServerSentEvent<?>> elicitationFlux = elicitationService.registerChat(chatId);

        Flux<ServerSentEvent<?>> cancelEvents = elicitationFlux
                .filter(sse -> CANCEL_ACTION.equalsIgnoreCase(sse.event()))
                .take(1)
                .share();

        Mono<ServerSentEvent<?>> initEvent = Mono.just(
                ServerSentEvent.builder(new ChunkPayload(""))
                        .event(INIT)
                        .id("0")
                        .build()
        );

        Flux<ServerSentEvent<ChunkPayload>> chunkObjects = Flux.defer(() -> promptService.stream(chatId, userId, chatMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(StringUtils::isNotEmpty)
                .doOnNext(assembled::append)
                .index()
                .map(tuple -> {
                    ChunkPayload chunkPayload = new ChunkPayload(tuple.getT2());
                    return ServerSentEvent.builder(chunkPayload)
                            .event(CHUNK)
                            .id(String.valueOf(tuple.getT1() + 1))
                            .build();
                });

        Flux<ServerSentEvent<?>> chunks = initEvent.concatWith(chunkObjects);

        Mono<ServerSentEvent<?>> normalDone = Mono.<ServerSentEvent<?>>fromCallable(() -> {
            ChatMessage responseMessage = new ChatMessage();
            responseMessage.setChatId(chatId);
            responseMessage.setMessageType(ASSISTANT);
            responseMessage.setMessage(assembled.toString());
            responseMessage.setModel(chatModel);

            SolesonicChatResponse solesonicChatResponse = new SolesonicChatResponse(chatId, responseMessage);

            log.info("Sending done event for chat id {}", chatId);

            return ServerSentEvent.builder(solesonicChatResponse).event(DONE).build();
        }).subscribeOn(Schedulers.boundedElastic());

        Flux<ServerSentEvent<?>> cancelResponse = cancelEvents.flatMap(_ -> {
            assembled.setLength(0);
            assembled.append(CHAT_CANCELED);
            ChunkPayload payload = new ChunkPayload(CHAT_CANCELED);
            ServerSentEvent<?> cancelChunk = ServerSentEvent.builder(payload).event(CHUNK).build();

            ChatMessage responseMessage = new ChatMessage();
            responseMessage.setChatId(chatId);
            responseMessage.setMessageType(SYSTEM);
            responseMessage.setMessage(assembled.toString());
            responseMessage.setModel(chatModel);
            chatMessageService.save(responseMessage);

            SolesonicChatResponse solesonicChatResponse = new SolesonicChatResponse(chatId, responseMessage);
            ServerSentEvent<?> doneEvent = ServerSentEvent.builder(solesonicChatResponse).event(DONE).build();

            return Flux.just(cancelChunk, doneEvent);
        });

        Flux<ServerSentEvent<?>> elicitationNonCancel = elicitationFlux
                .filter(serverSentEvent -> !CANCEL_ACTION.equalsIgnoreCase(serverSentEvent.event()));

        Flux<ServerSentEvent<?>> chunkFlow = chunks
                .takeUntilOther(cancelEvents);

        Flux<ServerSentEvent<?>> chunkWithDone = chunkFlow
                .concatWith(normalDone);

        Flux<ServerSentEvent<?>> pipeline = Flux.merge(elicitationNonCancel, chunkWithDone, cancelResponse)
                .takeUntil(sse -> DONE.equalsIgnoreCase(sse.event()));

        pipeline.subscribe(
                sink::tryEmitNext,
                error -> {
                    Throwable unwrapped = Exceptions.unwrap(error);
                    boolean isInterrupted = unwrapped instanceof InterruptedException
                            || (unwrapped.getCause() instanceof InterruptedException);

                    if (Exceptions.isCancel(unwrapped) || isInterrupted) {
                        log.info("Stream cancelled gracefully for chat id {}", chatId);
                        sink.tryEmitComplete();
                    } else {
                        log.error("Stream error for chat id {}", chatId, error);
                        sink.tryEmitError(error);
                    }
                    cleanup(chatId, userId);
                },
                () -> {
                    log.info("Stream completed for chat id {}", chatId);
                    sink.tryEmitComplete();
                    cleanup(chatId, userId);
                }
        );

        return sink;
    }

    private void cleanup(UUID chatId, UUID userId) {
        log.info("Cleaning up stream cache for chat id: {}", chatId);
        elicitationService.closeChat(chatId);
        streamCache.remove(chatId);

        if (userId != null) {
            userActiveStreams.remove(userId, chatId);
        }
    }
}
