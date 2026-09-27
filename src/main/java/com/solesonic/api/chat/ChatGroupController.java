package com.solesonic.api.chat;

import com.solesonic.model.chat.ChatOrderRequest;
import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.chat.group.ChatGroupRequest;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.service.chat.ChatGroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Optional sections a user files conversations under.
 * <p>
 * Its own path rather than a segment of {@code /chats}: {@code /chats/{chatId}} takes a UUID, so a
 * literal segment underneath it would be matched as a chat id and rejected as unconvertible before
 * ever reaching a handler.
 * <p>
 * There is no {@code userId} anywhere in these paths. The caller's identity comes only from the
 * bearer token, so there is nothing here to supply or spoof.
 */
@RestController
@RequestMapping("/chatgroups")
public class ChatGroupController {
    private static final Logger log = LoggerFactory.getLogger(ChatGroupController.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ChatGroupService chatGroupService;

    public ChatGroupController(ChatGroupService chatGroupService) {
        this.chatGroupService = chatGroupService;
    }

    @PostMapping
    public ResponseEntity<ChatGroup> create(@RequestBody ChatGroupRequest chatGroupRequest) {
        log.info("Creating chat group");
        ChatGroup chatGroup = chatGroupService.create(chatGroupRequest.name());

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{chatGroupId}")
                .buildAndExpand(chatGroup.getId())
                .toUri();

        return ResponseEntity.created(location).body(chatGroup);
    }

    /**
     * Updates a group — its name and its place among the caller's sections, which together are
     * everything about a group a client owns.
     * <p>
     * The body is the {@link ChatGroup} itself rather than a request record: the entity already
     * marks its id, {@code userId} and {@code timestamp} read-only on the wire, so what a client can
     * send is exactly what an update may write, and a record listing the same two fields would only
     * be a second place to keep that rule in step.
     * <p>
     * A pure update, and a full one: both writable fields are taken as sent, so a body that omits
     * {@code sortOrder} unplaces the group rather than leaving it where it was. No other group is
     * written — a client rearranging several sections states each one.
     * <p>
     * The same name validation {@code create} applies, so an update can never accept a name that
     * creating one would reject.
     */
    @PutMapping("/{chatGroupId}")
    public ResponseEntity<ChatGroup> update(@PathVariable UUID chatGroupId,
                                            @RequestBody ChatGroup chatGroup) {
        log.info("Updating chat group {}", chatGroupId);
        ChatGroup updated = chatGroupService.update(chatGroupId, chatGroup);

        return ResponseEntity.ok(updated);
    }

    /**
     * Deletes the section, never the conversations filed under it: each one is ungrouped and kept.
     * <p>
     * Not to be confused with {@code DELETE /chatgroups/{chatGroupId}/chats/{chatId}} on the
     * adjacent path, which unfiles a single conversation and leaves the group standing.
     * <p>
     * A repeat is a {@code 404} rather than a {@code 204}, matching {@code DELETE /chats/{chatId}} —
     * a client whose picture of the sidebar is stale should be told so.
     */
    @DeleteMapping("/{chatGroupId}")
    public ResponseEntity<Void> delete(@PathVariable UUID chatGroupId) {
        log.info("Deleting chat group {}", chatGroupId);
        chatGroupService.delete(chatGroupId);

        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<ChatGroup>> get() {
        log.info("Getting chat groups");

        return ResponseEntity.ok(chatGroupService.get());
    }

    @GetMapping("/{chatGroupId}")
    public ResponseEntity<ChatGroup> get(@PathVariable UUID chatGroupId) {
        log.info("Getting chat group {}", chatGroupId);

        return ResponseEntity.ok(chatGroupService.get(chatGroupId));
    }

    @GetMapping("/{chatGroupId}/chats")
    public ResponseEntity<PagedModel<Chat>> chats(
            @PathVariable UUID chatGroupId,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        // Only the window is taken from the request, for the same reason ChatController does it:
        // the ordering belongs to the repository query, and a caller-supplied sort appended to it
        // is either an unknown property or a perturbed ordering that paging cannot rely on.
        Pageable chatPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Getting chats in group {} page {} size {}",
                chatGroupId, chatPage.getPageNumber(), chatPage.getPageSize());
        Page<Chat> chats = chatGroupService.chats(chatGroupId, chatPage);

        return ResponseEntity.ok(new PagedModel<>(chats));
    }

    /**
     * Idempotent, which is why it is a {@code PUT}: filing a conversation that is already in this
     * group is a success, not a conflict.
     */
    @PutMapping("/{chatGroupId}/chats/{chatId}")
    public ResponseEntity<Void> addChat(@PathVariable UUID chatGroupId, @PathVariable UUID chatId) {
        log.info("Adding chat {} to group {}", chatId, chatGroupId);
        chatGroupService.addChat(chatGroupId, chatId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Moves a conversation within this group. Its place in the caller's whole list is untouched —
     * that one is moved through {@code PUT /chats/{chatId}/order}.
     */
    @PutMapping("/{chatGroupId}/chats/{chatId}/order")
    public ResponseEntity<Chat> reorderChat(@PathVariable UUID chatGroupId,
                                            @PathVariable UUID chatId,
                                            @RequestBody ChatOrderRequest chatOrderRequest) {
        log.info("Moving chat {} to position {} in group {}", chatId, chatOrderRequest.position(), chatGroupId);
        Chat chat = chatGroupService.reorderChat(chatGroupId, chatId, chatOrderRequest.position());

        return ResponseEntity.ok(chat);
    }

    /**
     * Unfiles one conversation, leaving the group standing. Not to be confused with
     * {@code DELETE /chatgroups/{chatGroupId}}, which deletes the group itself.
     */
    @DeleteMapping("/{chatGroupId}/chats/{chatId}")
    public ResponseEntity<Void> removeChat(@PathVariable UUID chatGroupId, @PathVariable UUID chatId) {
        log.info("Removing chat {} from group {}", chatId, chatGroupId);
        chatGroupService.removeChat(chatGroupId, chatId);

        return ResponseEntity.noContent().build();
    }
}
