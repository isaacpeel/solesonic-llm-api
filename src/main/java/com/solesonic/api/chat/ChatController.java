package com.solesonic.api.chat;

import com.solesonic.model.chat.ChatRenameRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.service.ollama.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/chats")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ChatService chatService;

    ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<PagedModel<Chat>> getUserChats(
            @PathVariable UUID userId,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        // Only the window is taken from the request. Ordering belongs to the repository query, and a
        // sort carried on the Pageable would be appended to it - an unknown ?sort= property throwing
        // PropertyReferenceException, a known one perturbing the ordering that paging depends on.
        Pageable chatPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Getting chats by user id {} page {} size {}", userId, chatPage.getPageNumber(), chatPage.getPageSize());
        Page<Chat> chats = chatService.getByUserId(userId, chatPage);

        return ResponseEntity.ok(new PagedModel<>(chats));
    }

    @GetMapping("/{chatId}")
    public ResponseEntity<Chat> get(@PathVariable UUID chatId) {
        log.info("Getting chat id {}", chatId);
        Chat chat = chatService.get(chatId);

        return ResponseEntity.ok(chat);
    }

    @PutMapping("/{chatId}/name")
    public ResponseEntity<Chat> rename(@PathVariable UUID chatId, @RequestBody ChatRenameRequest chatRenameRequest) {
        log.info("Renaming chat id {}", chatId);
        Chat chat = chatService.rename(chatId, chatRenameRequest.name());

        return ResponseEntity.ok(chat);
    }
}
