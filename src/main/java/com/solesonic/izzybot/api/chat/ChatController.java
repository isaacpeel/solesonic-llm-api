package com.solesonic.izzybot.api.chat;

import com.solesonic.izzybot.model.IzzyBotResponse;
import com.solesonic.izzybot.model.chat.ChatRequest;
import com.solesonic.izzybot.model.chat.history.Chat;
import com.solesonic.izzybot.service.ollama.ChatService;
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
    public ResponseEntity<IzzyBotResponse> create(@PathVariable UUID userId,
                                                  @RequestBody ChatRequest chatRequest) {
        IzzyBotResponse izzyBotResponse = chatService.create(userId, chatRequest);

        return ResponseEntity.ok(izzyBotResponse);
    }

    @PutMapping("/{chatId}")
    public ResponseEntity<IzzyBotResponse> update(@PathVariable UUID chatId,
                                                  @RequestBody ChatRequest chatRequest) {
        log.info("Continuing standard chat with chat id {}", chatId);

        IzzyBotResponse izzyBotResponse = chatService.update(chatId, chatRequest);

        return ResponseEntity.ok(izzyBotResponse);
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
