package com.solesonic.service.redis;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.service.chat.ElicitationService;
import com.solesonic.service.ollama.ChatMessageService;
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

import static com.solesonic.service.chat.ElicitationService.CANCEL_ACTION;
import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;
import static org.springframework.ai.chat.messages.MessageType.SYSTEM;

@Service
public class RedisStreamingChatService {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamingChatService.class);
    public static final String CHUNK = "chunk";
    public static final String INIT = "init";
    public static final String DONE = "done";
    public static final String CHAT_CANCELED = "Chat canceled.";

    public record ChunkPayload(String content) {
    }

    private final ChatRepository chatRepository;
    private final PromptService promptService;
    private final ElicitationService elicitationService;
    private final ChatMessageService chatMessageService;
    private final RedisStreamService redisStreamService;
    private final ActiveStreamTracker activeStreamTracker;

    public RedisStreamingChatService(ChatRepository chatRepository,
                                     PromptService promptService,
                                     ElicitationService elicitationService,
                                     ChatMessageService chatMessageService,
                                     RedisStreamService redisStreamService,
                                     ActiveStreamTracker activeStreamTracker) {
        this.chatRepository = chatRepository;
        this.promptService = promptService;
        this.elicitationService = elicitationService;
        this.chatMessageService = chatMessageService;
        this.redisStreamService = redisStreamService;
        this.activeStreamTracker = activeStreamTracker;
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

        return update(chatId, userId, chatRequest, null, authentication);
    }

    public Flux<ServerSentEvent<?>> update(UUID chatId,
                                           UUID userId,
                                           ChatRequest chatRequest,
                                           String lastEventId,
                                           Authentication authentication) {

        if(StringUtils.isNotEmpty(lastEventId)) {
            return redisStreamService.subscribe(chatId, userId, lastEventId);
        }

        //Start a chat stream with an init event
        return redisStreamService.getLatestOffset(chatId, userId)
                .flatMap(offset -> redisStreamService.publish(chatId, userId, INIT)
                        .thenReturn(offset))
                .flatMapMany(offset -> {
                    publishToRedisStream(chatId, userId, chatRequest, authentication);
                    return redisStreamService.subscribe(chatId, userId, offset);
                });
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

        Flux<ServerSentEvent<?>> elicitationFlux = elicitationService.registerChat(chatId);

        Flux<ServerSentEvent<?>> cancelEvents = elicitationFlux
                .filter(sse -> CANCEL_ACTION.equalsIgnoreCase(sse.event()))
                .take(1)
                .share();

        Flux<String> chunkObjects = Flux.defer(() -> promptService.stream(chatId, userId, chatRequest, authentication))
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

            SolesonicChatResponse solesonicChatResponse = new SolesonicChatResponse(chatId, responseMessage);

            log.info("Publishing done event to Redis for chat id {}", chatId);

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
                    } else {
                        log.error("Redis stream error for chat id {}", chatId, error);
                    }

                    return Mono.empty();
                })
                .doFinally(_ -> cleanup(chatId, userId))
                .subscribe();

        cancelResponse.subscribe();
    }

    private void cleanup(UUID chatId, UUID userId) {
        log.info("Cleaning up Redis stream for chat id: {}", chatId);

        elicitationService.closeChat(chatId);

        if (userId != null) {
            activeStreamTracker.remove(userId, chatId)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }
    }
}
