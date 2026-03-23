package com.solesonic.api.chat;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.service.ollama.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/users/{userId}")
    public ResponseEntity<List<Chat>> getUserChats(@PathVariable UUID userId) {
        log.info("Getting chats by user id {}", userId);
        List<Chat> chats = chatService.getByUserId(userId);

        return ResponseEntity.ok(chats);
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<Chat> get(@PathVariable UUID chatId) {
        log.info("Getting chat id {}", chatId);
        Chat chat = chatService.get(chatId);

        return ResponseEntity.ok(chat);
    }
}
