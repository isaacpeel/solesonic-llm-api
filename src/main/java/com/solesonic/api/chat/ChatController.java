package com.solesonic.api.chat;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.service.ollama.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/chats")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/users/{userId}")
    public ResponseEntity<SolesonicChatResponse> create(@PathVariable UUID userId,
                                                        @RequestBody ChatRequest chatRequest) {
        SolesonicChatResponse solesonicChatResponse = chatService.create(userId, chatRequest);

        return ResponseEntity.ok(solesonicChatResponse);
    }

    @PutMapping("/{chatId}")
    public ResponseEntity<SolesonicChatResponse> update(@PathVariable UUID chatId,
                                                        @RequestBody ChatRequest chatRequest) {
        log.info("Continuing standard chat with chat id {}", chatId);

        SolesonicChatResponse solesonicChatResponse = chatService.update(chatId, chatRequest);

        return ResponseEntity.ok(solesonicChatResponse);
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<List<Chat>> getUserChats(@PathVariable UUID userId) {
        List<Chat> chats = chatService.getByUserId(userId);

        return ResponseEntity.ok(chats);
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<Chat> get(@PathVariable UUID chatId) {
        Chat chat = chatService.get(chatId);

        return ResponseEntity.ok(chat);
    }
}
