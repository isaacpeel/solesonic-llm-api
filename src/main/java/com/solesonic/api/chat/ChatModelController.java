package com.solesonic.api.chat;

import com.solesonic.model.chat.model.ChatModelOptions;
import com.solesonic.model.chat.model.ChatModelRequest;
import com.solesonic.model.chat.model.ChatModelState;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.chat.ChatModelService;
import com.solesonic.service.chat.ChatService;
import com.solesonic.model.chat.history.ChatKind;
import com.solesonic.service.session.SharedSessionService;
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
 * The model a conversation uses (for a shared chat, any seated participant may choose it), chosen through plain REST: {@code /model} is a setting command the
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
    private final UserRequestContext userRequestContext;
    private final SharedSessionService sharedSessionService;

    public ChatModelController(ChatModelService chatModelService,
                               ChatService chatService,
                               UserRequestContext userRequestContext,
                               SharedSessionService sharedSessionService) {
        this.sharedSessionService = sharedSessionService;
        this.chatModelService = chatModelService;
        this.chatService = chatService;
        this.userRequestContext = userRequestContext;
    }

    /**
     * Without a chat, the new-chat composer's case: the list and the default only. The list is the
     * models the caller's own key may use.
     */
    @GetMapping("/models")
    public ResponseEntity<ChatModelOptions> options(@RequestParam(required = false) UUID chatId) {
        if (chatId == null) {
            log.info("Listing chat models");

            return ResponseEntity.ok(chatModelService.options(userRequestContext.getUserId()));
        }

        log.info("Listing chat models for chat {}", chatId);

        chatService.requireModelAccess(chatId);

        return ResponseEntity.ok(chatModelService.options(userRequestContext.getUserId(), chatId));
    }

    /**
     * A {@code PUT} because it is idempotent, like {@code /chats/{chatId}/name}.
     */
    @PutMapping("/{chatId}/model")
    public ResponseEntity<ChatModelState> select(@PathVariable UUID chatId, @RequestBody ChatModelRequest chatModelRequest) {
        log.info("Selecting a model for chat {}", chatId);

        ChatKind kind = chatService.requireModelAccess(chatId).getKind();

        ChatModelState state = chatModelService.select(userRequestContext.getUserId(), chatId, chatModelRequest.model());

        announce(chatId, kind);

        return ResponseEntity.ok(state);
    }

    /**
     * Returns the chat to the default, which follows the server's default if that changes — a
     * {@code PUT} of the default's name would pin it instead.
     */
    @DeleteMapping("/{chatId}/model")
    public ResponseEntity<Void> reset(@PathVariable UUID chatId) {
        log.info("Resetting the model for chat {}", chatId);

        ChatKind kind = chatService.requireModelAccess(chatId).getKind();
        chatModelService.reset(chatId);

        announce(chatId, kind);

        return ResponseEntity.noContent().build();
    }

    /**
     * A model change in a shared chat applies to everyone's next message, so everyone seated is told the new
     * state. A private chat has nobody to tell.
     */
    private void announce(UUID chatId, ChatKind kind) {
        if (kind == ChatKind.SHARED) {
            sharedSessionService.publishStateForChat(chatId);
        }
    }
}
