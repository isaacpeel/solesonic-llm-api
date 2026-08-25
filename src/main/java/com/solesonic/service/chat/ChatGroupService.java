package com.solesonic.service.chat;

import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.repository.chat.ChatGroupRepository;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.ollama.ChatService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Conversation groups: the sections a user files chats under.
 * <p>
 * Every method here is scoped to {@link UserRequestContext#getUserId()}, which is resolved from the
 * JWT subject rather than from a path segment, so a group or a chat belonging to someone else is
 * answered identically to one that does not exist. Grouping is optional and non-destructive in both
 * directions: a chat carries at most one group, filing it changes nothing else about it, and
 * removing it from a group leaves the conversation itself untouched.
 */
@Service
public class ChatGroupService {
    private static final Logger log = LoggerFactory.getLogger(ChatGroupService.class);

    private static final int MAX_NAME_LENGTH = 255;

    private final ChatGroupRepository chatGroupRepository;
    private final ChatRepository chatRepository;
    private final ChatService chatService;
    private final UserRequestContext userRequestContext;

    public ChatGroupService(ChatGroupRepository chatGroupRepository,
                            ChatRepository chatRepository,
                            ChatService chatService,
                            UserRequestContext userRequestContext) {
        this.chatGroupRepository = chatGroupRepository;
        this.chatRepository = chatRepository;
        this.chatService = chatService;
        this.userRequestContext = userRequestContext;
    }

    @Transactional
    public ChatGroup create(String name) {
        UUID userId = userRequestContext.getUserId();

        ChatGroup chatGroup = new ChatGroup();
        chatGroup.setUserId(userId);
        chatGroup.setName(validName(name));
        chatGroup.setTimestamp(ZonedDateTime.now());

        ChatGroup saved = chatGroupRepository.save(chatGroup);

        log.info("Created chat group {} for user {}", saved.getId(), userId);

        return saved;
    }

    /**
     * Updates a group: its name and its place among the caller's sections, which is everything about
     * a group a client owns. A full update rather than a patch — both fields are taken from the body
     * as sent, so omitting {@code sortOrder} unplaces the group rather than leaving it where it was.
     * <p>
     * The caller's ownership is resolved first and the body validated second, so a bad name or a
     * negative rank sent for a group the caller does not own is a {@code 404} rather than a
     * {@code 400} — the endpoint must not tell them the difference between "your body was wrong" and
     * "that group is not yours".
     * <p>
     * The same {@link #validName(String)} {@code create} uses, so an update can never accept a name
     * that creating one would reject. Names stay non-unique: giving a group a name another group
     * already carries is a success, and the listing already breaks that tie by id.
     * <p>
     * Only the two writable fields are read off {@code chatGroup}. It arrives deserialized from the
     * request body, so its id, {@code userId} and {@code timestamp} are whatever Jackson left there —
     * nothing a client sends, since all three are read-only on the wire. The id that matters is the
     * path's, and it has already been resolved against the caller.
     * <p>
     * Nothing else is touched, and no other row is written: the arrangement is the client's to state
     * one group at a time, which is what keeps this a pure update rather than the renumbering pass a
     * chat move runs. See {@link ChatGroup#getSortOrder()} for why gaps and duplicates are legal.
     */
    @Transactional
    public ChatGroup update(UUID chatGroupId, ChatGroup chatGroup) {
        UUID userId = userRequestContext.getUserId();

        ChatGroup existing = chatGroup(chatGroupId, userId);

        existing.setName(validName(chatGroup.getName()));
        existing.setSortOrder(validSortOrder(chatGroup.getSortOrder()));

        log.info("Updating chat group {} to position {} for user {}",
                chatGroupId, chatGroup.getSortOrder(), userId);

        return chatGroupRepository.save(existing);
    }

    /**
     * Deletes a group and ungroups the conversations filed under it.
     * <p>
     * Never deletes a conversation. The schema says the same thing from the other side —
     * {@code chat_chat_group_id_fkey} is {@code on delete set null} — because losing a section of
     * the sidebar must never lose the chats that were filed under it.
     * <p>
     * The chats are cleared explicitly rather than by the constraint: the database would null the
     * group id but leave {@code groupSortOrder} pointing into a group that no longer exists, and
     * Hibernate would never learn either had happened. Each chat's {@code sortOrder} — its place in
     * the user's whole list — is untouched; the two orderings are independent, and a deleted group
     * says nothing about the sidebar.
     * <p>
     * One transaction, so the group is gone and its chats are ungrouped, or nothing happened. A
     * repeat is a {@code 404} rather than a {@code 204}, matching {@code DELETE /chats/{chatId}}.
     */
    @Transactional
    public void delete(UUID chatGroupId) {
        UUID userId = userRequestContext.getUserId();

        ChatGroup chatGroup = chatGroup(chatGroupId, userId);

        int ungrouped = chatRepository.clearChatGroup(userId, chatGroup.getId());

        chatGroupRepository.delete(chatGroup);

        log.info("Deleted chat group {} and ungrouped {} chat(s) for user {}",
                chatGroupId, ungrouped, userId);
    }

    @Transactional(readOnly = true)
    public List<ChatGroup> get() {
        UUID userId = userRequestContext.getUserId();

        log.info("Getting chat groups for user {}", userId);

        return chatGroupRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public ChatGroup get(UUID chatGroupId) {
        return chatGroup(chatGroupId, userRequestContext.getUserId());
    }

    /**
     * Every conversation filed under one group, paged and ordered exactly as the ungrouped chat
     * list is, and hydrated with its messages the same way.
     */
    @Transactional(readOnly = true)
    public Page<Chat> chats(UUID chatGroupId, Pageable pageable) {
        UUID userId = userRequestContext.getUserId();

        ChatGroup chatGroup = chatGroup(chatGroupId, userId);

        return chatService.getByUserIdAndChatGroupId(userId, chatGroup.getId(), pageable);
    }

    /**
     * Files a conversation under a group, replacing whatever group it was in. Both ids are checked
     * against the caller before anything is written, so an id the caller does not own can neither
     * be read nor made a member of a group it does own.
     */
    @Transactional
    public void addChat(UUID chatGroupId, UUID chatId) {
        UUID userId = userRequestContext.getUserId();

        ChatGroup chatGroup = chatGroup(chatGroupId, userId);
        Chat chat = chat(chatId, userId);

        if (!chatGroup.getId().equals(chat.getChatGroupId())) {
            // A hand-placed position describes a place in the group the chat is leaving, so it does
            // not survive the move. Filing a chat into the group it is already in is left alone —
            // the operation is idempotent, and clearing the position would make it destructive.
            chat.setGroupSortOrder(null);
        }

        chat.setChatGroupId(chatGroup.getId());
        chatRepository.save(chat);

        log.info("Added chat {} to group {} for user {}", chatId, chatGroupId, userId);
    }

    /**
     * Moves a conversation within one group. Both ids are checked against the caller, and the chat
     * must actually be in the group — the same rule {@link #removeChat} applies, for the same
     * reason: a position in a group the conversation is not filed under describes nothing.
     */
    @Transactional
    public Chat reorderChat(UUID chatGroupId, UUID chatId, Integer position) {
        UUID userId = userRequestContext.getUserId();

        ChatGroup chatGroup = chatGroup(chatGroupId, userId);

        return chatService.reorderWithinGroup(chatGroup.getId(), chatId, position);
    }

    /**
     * Ungroups a conversation. A chat that is not in this group is a {@code 404} rather than a
     * silent success: the client's view of where the conversation lives is wrong, and reporting the
     * removal as done would leave it wrong.
     */
    @Transactional
    public void removeChat(UUID chatGroupId, UUID chatId) {
        UUID userId = userRequestContext.getUserId();

        ChatGroup chatGroup = chatGroup(chatGroupId, userId);
        Chat chat = chat(chatId, userId);

        if (!chatGroup.getId().equals(chat.getChatGroupId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Chat " + chatId + " is not in group " + chatGroupId);
        }

        chat.setChatGroupId(null);
        chat.setGroupSortOrder(null);
        chatRepository.save(chat);

        log.info("Removed chat {} from group {} for user {}", chatId, chatGroupId, userId);
    }

    private ChatGroup chatGroup(UUID chatGroupId, UUID userId) {
        return chatGroupRepository.findByIdAndUserId(chatGroupId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Chat group not found: " + chatGroupId));
    }

    private Chat chat(UUID chatId, UUID userId) {
        return chatRepository.findByIdAndUserId(chatId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Chat not found: " + chatId));
    }

    private String validName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chat group name must not be blank");
        }

        String trimmedName = name.trim();

        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chat group name must be " + MAX_NAME_LENGTH + " characters or fewer");
        }

        return trimmedName;
    }

    /**
     * Rejected for the same reason a chat's position is: a negative rank is a client bug, and there
     * is no arrangement it could describe. Null is not an error — it unplaces the group and returns
     * it to name ordering.
     */
    private Integer validSortOrder(Integer sortOrder) {
        if (sortOrder != null && sortOrder < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sort order must not be negative");
        }

        return sortOrder;
    }
}
