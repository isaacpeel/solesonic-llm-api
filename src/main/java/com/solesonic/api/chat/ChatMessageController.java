package com.solesonic.api.chat;

import com.solesonic.service.chat.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/chats/{chatId}/messages")
public class ChatMessageController {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageController.class);

    private final ChatMessageService chatMessageService;

    public ChatMessageController(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> delete(@PathVariable UUID chatId, @PathVariable UUID messageId) {
        log.info("Deleting chat message {} from chat {}", messageId, chatId);
        chatMessageService.delete(chatId, messageId);

        return ResponseEntity.noContent().build();
    }
}
