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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.springframework.ai.chat.messages.MessageType.ASSISTANT;

@Service
public class StreamingChatService {
    private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);

    private final ChatRepository chatRepository;
    private final PromptService promptService;
    private final ElicitationService elicitationService;
    private final UserRequestContext userRequestContext;

    public StreamingChatService(ChatRepository chatRepository,
                                PromptService promptService,
                                ElicitationService elicitationService,
                                UserRequestContext userRequestContext) {
        this.chatRepository = chatRepository;
        this.promptService = promptService;
        this.elicitationService = elicitationService;
        this.userRequestContext = userRequestContext;
    }

    private String removeThinkTags(String message) {
        if (message == null) {
            return null;
        }

        return message.replaceAll("<think>.*?</think>", "");
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

    public Flux<ServerSentEvent<?>> update(UUID chatId, ChatRequest chatRequest) {
        String chatMessage = chatRequest.chatMessage();
        String chatModel = promptService.model();
        StringBuilder assembled = new StringBuilder();

        // make chat id available for downstream components (e.g., MCP elicitation handler)
        userRequestContext.setChatId(chatId);

        Flux<ServerSentEvent<?>> elicitationFlux = elicitationService.registerChat(chatId);

        Flux<ServerSentEvent<Object>> chunkObjects = promptService.stream(chatId, chatMessage)
                .filter(chunk -> chunk != null && !chunk.isEmpty())
                .doOnNext(assembled::append)
                .map(chunk -> ServerSentEvent.builder((Object) chunk)
                        .event("chunk")
                        .build())
                .doFinally(signalType -> {
                    
                    log.info("Closing elicitation for chat id: {}", chatId);
                    
                    elicitationService.closeChat(chatId);
                });
        
        Flux<ServerSentEvent<?>> chunks = chunkObjects.map(sse -> (ServerSentEvent<?>) sse);

        Mono<? extends ServerSentEvent<?>> done = Mono.fromSupplier(() -> {
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

        log.info("Finished streaming chat with chat id {}", chatId);
        // Merge token chunks with any out-of-band elicitation events; ensure done is last
        return Flux.merge(elicitationFlux, chunks).concatWith(done.map(sse -> (ServerSentEvent<?>) sse));
    }
}
