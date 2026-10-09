package com.solesonic.api.chat;

import com.solesonic.model.chat.model.ChatModelOptions;
import com.solesonic.model.chat.model.ChatModelRequest;
import com.solesonic.model.chat.model.ChatModelState;
import com.solesonic.service.chat.ChatModelService;
import com.solesonic.service.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The model a conversation uses, chosen through plain REST: {@code /model} is a setting command the
 * client applies itself, never a chat turn.
 * <p>
 * {@code GET /chats/models} shares its first segment with {@code GET /chats/{chatId}}; Spring ranks a
 * literal segment above a template, so the two never compete. The options are a collection filtered
 * by an optional chat, the same shape as {@code /chats/documents?chatId=}.
 */
@RestController
@RequestMapping("/chats")
public class ChatModelController {
    private static final Logger log = LoggerFactory.getLogger(ChatModelController.class);

    private final ChatModelService chatModelService;
    private final ChatService chatService;

    public ChatModelController(ChatModelService chatModelService, ChatService chatService) {
        this.chatModelService = chatModelService;
        this.chatService = chatService;
    }

    /**
     * Without a chat, the new-chat composer's case: the list and the default only.
     */
    @GetMapping("/models")
    public ResponseEntity<ChatModelOptions> options(@RequestParam(required = false) UUID chatId) {
        if (chatId == null) {
            log.info("Listing chat models");

            return ResponseEntity.ok(chatModelService.options());
        }

        log.info("Listing chat models for chat {}", chatId);

        chatService.requireOwned(chatId);

        return ResponseEntity.ok(chatModelService.options(chatId));
    }

    /**
     * A {@code PUT} because it is idempotent, like {@code /chats/{chatId}/name}.
     */
    @PutMapping("/{chatId}/model")
    public ResponseEntity<ChatModelState> select(@PathVariable UUID chatId, @RequestBody ChatModelRequest chatModelRequest) {
        log.info("Selecting a model for chat {}", chatId);

        chatService.requireOwned(chatId);

        return ResponseEntity.ok(chatModelService.select(chatId, chatModelRequest.model()));
    }

    /**
     * Returns the chat to the default, which follows the server's default if that changes — a
     * {@code PUT} of the default's name would pin it instead.
     */
    @DeleteMapping("/{chatId}/model")
    public ResponseEntity<Void> reset(@PathVariable UUID chatId) {
        log.info("Resetting the model for chat {}", chatId);

        chatService.requireOwned(chatId);
        chatModelService.reset(chatId);

        return ResponseEntity.noContent().build();
    }
}
