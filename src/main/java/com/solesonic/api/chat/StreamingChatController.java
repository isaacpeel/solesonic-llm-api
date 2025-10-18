package com.solesonic.api.chat;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.service.ollama.StreamingChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
@RequestMapping("/streaming/chats")
public class StreamingChatController {
    private static final Logger log = LoggerFactory.getLogger(StreamingChatController.class);

    private final StreamingChatService streamingChatService;

    public StreamingChatController(StreamingChatService streamingChatService) {
        this.streamingChatService = streamingChatService;
    }

    @PostMapping(value = "/users/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> create(@PathVariable UUID userId,
                               @RequestBody ChatRequest chatRequest) {
        log.info("Starting streaming chat for user {}", userId);

        return streamingChatService.create(userId, chatRequest);
    }

    @PutMapping(value = "/{chatId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> update(@PathVariable UUID chatId,
                               @RequestBody ChatRequest chatRequest) {
        log.info("Continuing streaming chat with chat id {}", chatId);

        return streamingChatService.update(chatId, chatRequest);
    }
}
