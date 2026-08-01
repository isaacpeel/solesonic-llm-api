package com.solesonic.api.chat;

import com.solesonic.mcp.client.elicitation.ElicitationProvider;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.service.chat.ChatStreamAccessService;
import com.solesonic.service.chat.ChatStreamAccessService.ChatAccess;
import com.solesonic.service.chat.events.ElicitationService;
import com.solesonic.service.redis.RedisStreamingChatService;
import com.solesonic.service.redis.StreamResumeService;
import com.solesonic.util.SseResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
    private final StreamResumeService streamResumeService;
    private final ChatStreamAccessService chatStreamAccessService;

    public StreamingChatController(RedisStreamingChatService streamingChatService,
                                   ElicitationService elicitationService,
                                   StreamResumeService streamResumeService,
                                   ChatStreamAccessService chatStreamAccessService) {
        this.streamingChatService = streamingChatService;
        this.elicitationService = elicitationService;
        this.streamResumeService = streamResumeService;
        this.chatStreamAccessService = chatStreamAccessService;
    }

    @PostMapping(value = "/users/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<?>>> create(@PathVariable UUID userId,
                                                           @RequestBody ChatRequest chatRequest,
                                                           Authentication authentication) {

        log.info("Starting streaming chat for user {}", userId);

        if (chatStreamAccessService.forNewChat(authentication, userId) == ChatAccess.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return SseResponse.ok(streamingChatService.create(userId, chatRequest, authentication));
    }

    @PutMapping(value = "/{chatId}/users/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<?>>> update(@PathVariable UUID userId,
                                                           @PathVariable UUID chatId,
                                                           @RequestBody ChatRequest chatRequest,
                                                           @RequestHeader(value = LAST_EVENT_ID, required = false) String lastEventId,
                                                           Authentication authentication) {
        log.info("Continuing streaming chat with chat id: {} and last event id: {}", chatId, lastEventId);

        //Resuming over PUT predates the resume endpoint and is kept working for clients that
        //already do it; it cannot report why a resume failed, which is why the GET exists.
        if (StringUtils.isNotEmpty(lastEventId)) {
            return streamResumeService.resume(authentication, chatId, userId, lastEventId);
        }

        ChatAccess chatAccess = chatStreamAccessService.forExistingChat(authentication, chatId, userId);

        if (chatAccess == ChatAccess.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (chatAccess == ChatAccess.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }

        return SseResponse.ok(streamingChatService.update(chatId, userId, chatRequest, authentication));
    }

    /**
     * Replays the frames a client missed and then continues live through {@code done}.
     * <p>
     * A {@code GET} rather than a repeat of the {@code PUT}: resuming must never re-run the turn,
     * and the request that recovers a turn should be the one method a client can safely retry.
     * The cursor is the {@code id:} of the last frame the client saw — a Redis stream id, echoed
     * back verbatim.
     */
    @GetMapping(value = "/{chatId}/users/{userId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<?>>> resume(@PathVariable UUID chatId,
                                                           @PathVariable UUID userId,
                                                           @RequestHeader(value = LAST_EVENT_ID, required = false) String lastEventIdHeader,
                                                           @RequestParam(value = "lastEventId", required = false) String lastEventIdParameter,
                                                           Authentication authentication) {

        String lastEventId = StringUtils.isNotEmpty(lastEventIdHeader) ? lastEventIdHeader : lastEventIdParameter;

        log.info("Resuming stream for chat {} from last event id {}", chatId, lastEventId);

        return streamResumeService.resume(authentication, chatId, userId, lastEventId);
    }

    @PostMapping(value = "/{chatId}/{elicitationId}/elicitation-response")
    public Mono<ResponseEntity<Void>> submitElicitationResponse(@PathVariable UUID chatId,
                                                                @PathVariable UUID elicitationId,
                                                                @RequestBody ElicitationProvider.ElicitationActionResult elicitationActionResult) {
        log.info("Received elicitation response for chat {}", chatId);

        assert elicitationActionResult.elicitationId().equals(elicitationId);

        return elicitationService.completeFromFrontend(elicitationActionResult)
                .map(completed -> completed
                        ? ResponseEntity.ok().build()
                        : ResponseEntity.notFound().build());
    }
}
