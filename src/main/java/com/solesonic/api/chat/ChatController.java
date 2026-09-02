package com.solesonic.api.chat;

import com.solesonic.model.chat.ChatOrderRequest;
import com.solesonic.model.chat.ChatRenameRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.security.ResourceOwnershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/chats")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ChatService chatService;
    private final ResourceOwnershipService resourceOwnershipService;

    ChatController(ChatService chatService, ResourceOwnershipService resourceOwnershipService) {
        this.chatService = chatService;
        this.resourceOwnershipService = resourceOwnershipService;
    }

    /**
     * @param ungrouped when true, only the conversations that are not filed under a group. It
     *                  defaults to false, which is every chat the user owns - the behaviour every
     *                  existing client already depends on. It exists because a client rendering
     *                  group sections above this list would otherwise show every grouped
     *                  conversation twice, and filtering them out client-side leaves the page
     *                  metadata describing more rows than the client will render.
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<PagedModel<Chat>> getUserChats(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "false") boolean ungrouped,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable,
            HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Pageable chatPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Getting chats by user id {} ungrouped {} page {} size {}",
                userId, ungrouped, chatPage.getPageNumber(), chatPage.getPageSize());

        Page<Chat> chats = ungrouped
                ? chatService.getUngroupedByUserId(userId, chatPage)
                : chatService.getByUserId(userId, chatPage);

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

    /**
     * Moves a conversation within the caller's whole list, independently of any group it is filed
     * under. A {@code PUT} because it is idempotent: sending the same position twice leaves the
     * list in the same arrangement.
     */
    @PutMapping("/{chatId}/order")
    public ResponseEntity<Chat> reorder(@PathVariable UUID chatId, @RequestBody ChatOrderRequest chatOrderRequest) {
        log.info("Moving chat id {} to position {}", chatId, chatOrderRequest.position());
        Chat chat = chatService.reorder(chatId, chatOrderRequest.position());

        return ResponseEntity.ok(chat);
    }

    @DeleteMapping("/{chatId}")
    public ResponseEntity<Void> delete(@PathVariable UUID chatId) {
        log.info("Deleting chat id {}", chatId);
        chatService.delete(chatId);

        return ResponseEntity.noContent().build();
    }
}
