package com.solesonic.service.ollama;

import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.ollama.ChatMessageRepository;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_NAME_LENGTH = 255;

    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatAttachmentService chatAttachmentService;
    private final GeneratedImageService generatedImageService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final RedisStreamService redisStreamService;
    private final UserRequestContext userRequestContext;

    private String removeThinkTags(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("<think>.*?</think>", "");
    }

    public ChatService(
            ChatRepository chatRepository,
            ChatMessageRepository chatMessageRepository,
            ChatAttachmentService chatAttachmentService,
            GeneratedImageService generatedImageService,
            A2AStickyAgentService a2aStickyAgentService,
            RedisStreamService redisStreamService,
            UserRequestContext userRequestContext) {
        this.chatRepository = chatRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatAttachmentService = chatAttachmentService;
        this.generatedImageService = generatedImageService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.redisStreamService = redisStreamService;
        this.userRequestContext = userRequestContext;
    }

    public Page<Chat> getByUserId(UUID userId, Pageable pageable) {
        log.info("Getting chats by user id {} page {} size {}", userId, pageable.getPageNumber(), pageable.getPageSize());
        Page<Chat> chats = chatRepository.findByUserId(userId, pageable);

        return withMessages(chats);
    }

    /**
     * The same page of chats, narrowed to one group. Ownership of the group is the caller's to
     * establish — {@code ChatGroupService} does it before reaching here — but the chats themselves
     * are still filtered by user at the query.
     */
    public Page<Chat> getByUserIdAndChatGroupId(UUID userId, UUID chatGroupId, Pageable pageable) {
        log.info("Getting chats by user id {} group {} page {} size {}",
                userId, chatGroupId, pageable.getPageNumber(), pageable.getPageSize());
        Page<Chat> chats = chatRepository.findByUserIdAndChatGroupId(userId, chatGroupId, pageable);

        return withMessages(chats);
    }

    /**
     * The same page of chats, narrowed to the ones that are not filed under any group.
     * <p>
     * A separate method rather than a flag on {@link #getByUserId(UUID, Pageable)}: a boolean at a
     * call site reads as {@code getByUserId(userId, pageable, true)} and tells a reader nothing. The
     * controller is the one place that knows the request asked for a filter.
     */
    public Page<Chat> getUngroupedByUserId(UUID userId, Pageable pageable) {
        log.info("Getting ungrouped chats by user id {} page {} size {}",
                userId, pageable.getPageNumber(), pageable.getPageSize());
        Page<Chat> chats = chatRepository.findUngroupedByUserId(userId, pageable);

        return withMessages(chats);
    }

    public Chat get(UUID chatId) {
        Chat chat = chatRepository.findById(chatId).orElse(null);

        if (chat == null) {
            return null;
        }

        chat.setChatMessages(chatMessages(chatId));

        return chat;
    }

    /**
     * Sets a chat's display name, scoped to the caller: {@link UserRequestContext#getUserId()}
     * comes only from the JWT subject, never from a client-supplied path segment, so a chat owned
     * by someone else is indistinguishable from one that does not exist.
     */
    public Chat rename(UUID chatId, String name) {
        if (StringUtils.isBlank(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chat name must not be blank");
        }

        String trimmedName = name.trim();

        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chat name must be " + MAX_NAME_LENGTH + " characters or fewer");
        }

        UUID userId = userRequestContext.getUserId();

        Chat chat = ownedChat(chatId, userId);

        chat.setName(trimmedName);

        log.info("Renaming chat {} for user {}", chatId, userId);

        return chatRepository.save(chat);
    }

    /**
     * Moves a conversation within the caller's whole list.
     * <p>
     * Scoped exactly as {@link #rename(UUID, String)} is: the owner comes from the JWT subject, so
     * a chat belonging to someone else cannot be moved and cannot be told apart from one that does
     * not exist.
     *
     * @param position zero-based index among the conversations already placed by hand, or
     *                 {@code null} to unplace this one and return it to timestamp ordering
     */
    @Transactional
    public Chat reorder(UUID chatId, Integer position) {
        requireValidPosition(position);

        UUID userId = userRequestContext.getUserId();
        Chat chat = ownedChat(chatId, userId);

        log.info("Moving chat {} to position {} for user {}", chatId, position, userId);

        return place(chat,
                position,
                chatRepository.findPlacedByUserId(userId),
                Chat::getSortOrder,
                Chat::setSortOrder);
    }

    /**
     * Moves a conversation within one group, leaving its place in the whole list alone.
     * <p>
     * The group has already been checked against the caller by {@code ChatGroupService}; the chat
     * is checked here, and its membership of that group is checked as well — a conversation cannot
     * be given a position in a group it is not filed under.
     */
    @Transactional
    public Chat reorderWithinGroup(UUID chatGroupId, UUID chatId, Integer position) {
        requireValidPosition(position);

        UUID userId = userRequestContext.getUserId();
        Chat chat = ownedChat(chatId, userId);

        if (!chatGroupId.equals(chat.getChatGroupId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Chat " + chatId + " is not in group " + chatGroupId);
        }

        log.info("Moving chat {} to position {} in group {} for user {}", chatId, position, chatGroupId, userId);

        return place(chat,
                position,
                chatRepository.findPlacedByUserIdAndChatGroupId(userId, chatGroupId),
                Chat::getGroupSortOrder,
                Chat::setGroupSortOrder);
    }

    /**
     * Deletes a conversation and everything stored under it: its messages, the images attached to
     * them, and the images generated inside it.
     * <p>
     * The cascade is written out here because the database will not do it — none of those tables
     * carries a foreign key to {@code chat}, so deleting the row alone would leave a transcript and
     * megabytes of image bytes behind, unreachable but still stored. All of it is one transaction,
     * so a conversation is either gone or untouched.
     * <p>
     * A turn still in flight is not cancelled by this. Generation is deliberately independent of
     * any listener, so a message written after the delete lands on a chat that no longer exists and
     * is unreachable from every read path. Clients should not delete a conversation mid-turn.
     */
    @Transactional
    public void delete(UUID chatId) {
        UUID userId = userRequestContext.getUserId();
        Chat chat = ownedChat(chatId, userId);

        log.info("Deleting chat {} for user {}", chatId, userId);

        chatAttachmentService.deleteForChat(chatId);
        generatedImageService.deleteForChat(chatId);

        int messages = chatMessageRepository.deleteByChatId(chatId);

        chatRepository.delete(chat);

        log.info("Deleted chat {} and {} message(s) for user {}", chatId, messages, userId);

        discardTransientState(chatId, userId);
    }

    /**
     * Rewrites the arrangement of one list so that {@code chat} lands at {@code position}.
     * <p>
     * The list is renumbered densely from zero and only the rows whose number actually changed are
     * written, so a move near the top of a long sidebar costs a handful of updates rather than one
     * per conversation. Which of the two positions is being rewritten is passed in rather than
     * branched on: the arrangement logic is identical for the whole list and for a group, and the
     * only difference is the column.
     * <p>
     * Density holds only until something leaves the list without a move — a deleted chat, or one
     * that changed group — which is why nothing may read a position as an index. Ordering is
     * relative, and the next move through here closes the gap.
     *
     * @param placed the conversations that already carry a position, in rendered order
     */
    private Chat place(Chat chat,
                       Integer position,
                       List<Chat> placed,
                       Function<Chat, Integer> currentPosition,
                       BiConsumer<Chat, Integer> assignPosition) {
        List<Chat> arrangement = new ArrayList<>(placed);
        arrangement.removeIf(candidate -> candidate.getId().equals(chat.getId()));

        List<Chat> moved = new ArrayList<>();

        if (position == null) {
            assignPosition.accept(chat, null);
            moved.add(chat);
        } else {
            // A position past the end appends: a client dragging into the timestamp-ordered part of
            // the list means "last", and failing the move would only make that gesture unusable.
            arrangement.add(Math.min(position, arrangement.size()), chat);
        }

        for (int index = 0; index < arrangement.size(); index++) {
            Chat candidate = arrangement.get(index);

            if (!Integer.valueOf(index).equals(currentPosition.apply(candidate))) {
                assignPosition.accept(candidate, index);
                moved.add(candidate);
            }
        }

        chatRepository.saveAll(moved);

        return chat;
    }

    /**
     * Rejected before anything is read: a negative index is a client bug, and there is no
     * arrangement it could describe. A position past the end is not an error — it appends.
     */
    private void requireValidPosition(Integer position) {
        if (position != null && position < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Position must not be negative");
        }
    }

    private Chat ownedChat(UUID chatId, UUID userId) {
        return chatRepository.findByIdAndUserId(chatId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId));
    }

    /**
     * Drops the Redis state a deleted conversation leaves behind: any sticky A2A agent and task
     * bound to it, and its stream buffer.
     * <p>
     * Best effort, and deliberately not allowed to fail the delete. Every one of these keys carries
     * a TTL and none of them means anything without the chat row, so the worst a failure here costs
     * is a few kilobytes that expire on their own.
     */
    private void discardTransientState(UUID chatId, UUID userId) {
        a2aStickyAgentService.deactivate(chatId)
                .then(a2aStickyAgentService.deactivateTask(chatId))
                .then(redisStreamService.deleteStream(chatId, userId).then())
                .doOnError(error -> log.warn("Could not discard Redis state for deleted chat {}: {}",
                        chatId, error.getMessage()))
                .onErrorComplete()
                .subscribe();
    }

    private Page<Chat> withMessages(Page<Chat> chats) {
        for (Chat chat : chats) {
            chat.setChatMessages(chatMessages(chat.getId()));
        }

        return chats;
    }

    private List<ChatMessage> chatMessages(UUID chatId) {
        List<ChatMessage> chatMessages = chatMessageRepository.findByChatId(chatId);

        // One attachment query per chat, not per message.
        Map<UUID, List<ChatAttachmentSummary>> attachmentsByMessageId = chatAttachmentService.forChat(chatId)
                .stream()
                .collect(Collectors.groupingBy(ChatAttachmentSummary::chatMessageId));

        // Likewise one image query per chat. References only — the bytes stay in the table and are
        // fetched per image from the download endpoint, which is what keeps a conversation with a
        // dozen images a few kilobytes of JSON rather than tens of megabytes.
        Map<UUID, List<GeneratedImageSummary>> generatedImagesByMessageId = generatedImageService.forChat(chatId)
                .stream()
                .collect(Collectors.groupingBy(GeneratedImageSummary::chatMessageId));

        for (ChatMessage chatMessage : chatMessages) {
            // Remove <think>...</think> tags from each message
            String message = chatMessage.getMessage();
            if (message != null) {
                chatMessage.setMessage(removeThinkTags(message));
            }

            chatMessage.setAttachments(attachmentsByMessageId.getOrDefault(chatMessage.getId(), List.of()));
            chatMessage.setGeneratedImages(
                    generatedImagesByMessageId.getOrDefault(chatMessage.getId(), List.of()));
        }

        return chatMessages;
    }
}
