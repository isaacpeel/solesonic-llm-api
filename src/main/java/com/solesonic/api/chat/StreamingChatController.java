package com.solesonic.api.chat;

import com.solesonic.mcp.client.elicitation.ElicitationProvider;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.service.chat.ElicitationService;
import com.solesonic.service.redis.RedisStreamingChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/streaming/chats")
public class StreamingChatController {
    private static final Logger log = LoggerFactory.getLogger(StreamingChatController.class);
    public static final String LAST_EVENT_ID = "Last-Event-ID";

    private final RedisStreamingChatService streamingChatService;
    private final ElicitationService elicitationService;

    public StreamingChatController(RedisStreamingChatService streamingChatService,
                                   ElicitationService elicitationService) {
        this.streamingChatService = streamingChatService;
        this.elicitationService = elicitationService;
    }

    @PostMapping(value = "/users/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> create(@PathVariable UUID userId,
                                           @RequestBody ChatRequest chatRequest,
                                           @RequestHeader(value = LAST_EVENT_ID, required = false) String lastEventId,
                                           Authentication authentication) {
        
        log.info("Starting streaming chat for user {}", userId);
        log.info("last event id {}", lastEventId);

        return streamingChatService.create(userId, chatRequest, authentication);
    }

    @PutMapping(value = "/{chatId}/users/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> update(@PathVariable UUID userId,
                                           @PathVariable UUID chatId,
                                           @RequestBody ChatRequest chatRequest,
                                           @RequestHeader(value = LAST_EVENT_ID, required = false) String lastEventId,
                                           Authentication authentication) {
        log.info("Continuing streaming chat with chat id: {} and last event id: {}", chatId, lastEventId);

        return streamingChatService.update(chatId, userId, chatRequest, lastEventId, authentication);
    }

    @PostMapping(value = "/{chatId}/{elicitationId}/elicitation-response")
    public Mono<ResponseEntity<Void>> submitElicitationResponse(@PathVariable UUID chatId,
                                                                @PathVariable UUID elicitationId,
                                                                @RequestBody ElicitationProvider.DeleteConfirmation deleteConfirmation) {
        log.info("Received elicitation response for chat {}", chatId);

        assert deleteConfirmation.elicitationId().equals(elicitationId);

        return elicitationService.completeFromFrontend(deleteConfirmation)
                .map(completed -> completed
                        ? ResponseEntity.ok().build()
                        : ResponseEntity.notFound().build());
    }
}
