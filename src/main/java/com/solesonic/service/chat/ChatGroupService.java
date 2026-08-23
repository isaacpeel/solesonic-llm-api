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

        chat.setChatGroupId(chatGroup.getId());
        chatRepository.save(chat);

        log.info("Added chat {} to group {} for user {}", chatId, chatGroupId, userId);
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
}
