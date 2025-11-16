package com.solesonic.service.ollama;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.chat.ElicitationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
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
public class StreamingChatService {
    private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);
    public static final String CHUNK = "chunk";

    private final ChatRepository chatRepository;
    private final PromptService promptService;
    private final ElicitationService elicitationService;
    private final UserRequestContext userRequestContext;
    private final ChatMessageService chatMessageService;

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

    public Flux<ServerSentEvent<?>> create(UUID userId, ChatRequest chatRequest) {
        Chat chat = new Chat();
        chat.setUserId(userId);
        chat = save(chat);
        UUID chatId = chat.getId();

        log.debug("Starting streaming chat with new chat id {}", chatId);

        return update(chatId, chatRequest);
    }

    public record ChunkPayload(String content) {}

    public Flux<ServerSentEvent<?>> update(UUID chatId, ChatRequest chatRequest) {
        String chatMessage = chatRequest.chatMessage();
        String chatModel = promptService.model();
        StringBuilder assembled = new StringBuilder();

        userRequestContext.setChatId(chatId);

        Flux<ServerSentEvent<?>> elicitationFlux = elicitationService.registerChat(chatId);

        Flux<ServerSentEvent<?>> cancelEvents = elicitationFlux
                .filter(sse -> CANCEL_ACTION.equalsIgnoreCase(sse.event()))
                .take(1)
                .share();

        Flux<ServerSentEvent<ChunkPayload>> chunkObjects = promptService.stream(chatId, chatMessage)
                .subscribeOn(Schedulers.boundedElastic())
                .filter(chunk -> chunk != null && !chunk.isEmpty())
                .doOnNext(assembled::append)
                .map(chunk -> {
                    ChunkPayload payload = new ChunkPayload(chunk);
                    return ServerSentEvent.builder(payload)
                            .event(CHUNK)
                            .build();
                })
                .onErrorResume(throwable -> {

                    Throwable unwrapped = Exceptions.unwrap(throwable);

                    boolean isInterrupted = unwrapped instanceof InterruptedException
                            || (unwrapped.getCause() instanceof InterruptedException);

                    if (Exceptions.isCancel(unwrapped) || isInterrupted) {

                        log.info("Chunk stream interrupted/cancelled gracefully for chat id {}", chatId);

                        return Flux.empty();
                    }

                    return Flux.error(throwable);
                })
                .doFinally(signalType -> {
                    log.info("Closing elicitation for chat id: {}", chatId);

                    elicitationService.closeChat(chatId);
                });

        Flux<ServerSentEvent<?>> chunks = chunkObjects.map(sse -> sse);

        Mono<? extends ServerSentEvent<?>> normalDone = Mono.fromSupplier(() -> {
            ChatMessage responseMessage = new ChatMessage();
            responseMessage.setChatId(chatId);
            responseMessage.setMessageType(ASSISTANT);
            responseMessage.setMessage(assembled.toString());
            responseMessage.setModel(chatModel);

            SolesonicChatResponse resp = new SolesonicChatResponse(chatId, responseMessage);

            log.info("Sending done event.");

            return ServerSentEvent.builder(resp)
                    .event("done")
                    .build();
        });

        Flux<ServerSentEvent<?>> cancelResponse = cancelEvents.flatMap(_ -> {
            assembled.setLength(0);
            assembled.append("Chat canceled.");

            ServerSentEvent<?> cancelChunk = ServerSentEvent.builder((Object) "Chat canceled.")
                    .event(CHUNK)
                    .build();

            ChatMessage responseMessage = new ChatMessage();
            responseMessage.setChatId(chatId);
            responseMessage.setMessageType(SYSTEM);
            responseMessage.setMessage(assembled.toString());
            responseMessage.setModel(chatModel);

            chatMessageService.save(responseMessage);

            SolesonicChatResponse resp = new SolesonicChatResponse(chatId, responseMessage);

            ServerSentEvent<?> doneEvent = ServerSentEvent.builder(resp)
                    .event("done")
                    .build();

            return Flux.just(cancelChunk, doneEvent);
        });

        Flux<ServerSentEvent<?>> elicitationNonCancel = elicitationFlux.filter(serverSentEvent -> !CANCEL_ACTION.equalsIgnoreCase(serverSentEvent.event()));
        Flux<ServerSentEvent<?>> chunkFlow = chunks
                .takeUntilOther(cancelEvents)
                .onErrorResume(throwable -> {
                    Throwable unwrapped = Exceptions.unwrap(throwable);

                    boolean isInterrupted = unwrapped instanceof InterruptedException
                            || (unwrapped.getCause() instanceof InterruptedException);

                    if (Exceptions.isCancel(unwrapped) || isInterrupted) {

                        log.info("Chunk flow cancelled gracefully for chat id {}", chatId);

                        return Flux.empty();
                    }

                    return Flux.error(throwable);
                });

        log.info("Finished streaming chat with chat id {}", chatId);

        return Flux.merge(elicitationNonCancel, chunkFlow, cancelResponse)
                .concatWith(normalDone.map(sse -> (ServerSentEvent<?>) sse))
                .takeUntil(sse -> "done".equalsIgnoreCase(sse.event()));
    }
}
