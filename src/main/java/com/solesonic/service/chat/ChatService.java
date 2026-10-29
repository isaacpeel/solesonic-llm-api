package com.solesonic.service.chat;

import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatKind;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.interest.InterestUpdateService;
import com.solesonic.service.memory.UserMemoryService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int MAX_NAME_LENGTH = 255;

    private static final int MIN_SEARCH_LENGTH = 2;
    private static final int MAX_SEARCH_LENGTH = 200;

    /**
     * {@code SYSTEM} and {@code TOOL} rows hold elicitation JSON, tool output and progress data —
     * nothing the user saw as conversation text.
     */
    static final Set<MessageType> SEARCHABLE_MESSAGE_TYPES = Set.of(MessageType.USER, MessageType.ASSISTANT);

    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatAttachmentService chatAttachmentService;
    private final GeneratedImageService generatedImageService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final RedisStreamService redisStreamService;
    private final ChatModelSelection chatModelSelection;
    private final UserMemoryService userMemoryService;
    private final InterestUpdateService interestUpdateService;
    private final UserRequestContext userRequestContext;

    public ChatService(
            ChatRepository chatRepository,
            ChatMessageRepository chatMessageRepository,
            ChatAttachmentService chatAttachmentService,
            GeneratedImageService generatedImageService,
            A2AStickyAgentService a2aStickyAgentService,
            RedisStreamService redisStreamService,
            ChatModelSelection chatModelSelection,
            UserMemoryService userMemoryService,
            InterestUpdateService interestUpdateService,
            UserRequestContext userRequestContext) {
        this.chatRepository = chatRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatAttachmentService = chatAttachmentService;
        this.generatedImageService = generatedImageService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.redisStreamService = redisStreamService;
        this.chatModelSelection = chatModelSelection;
        this.userMemoryService = userMemoryService;
        this.interestUpdateService = interestUpdateService;
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

    /**
     * The user's conversations, grouped or not, whose name or {@code USER}/{@code ASSISTANT} text
     * contains {@code query}, ignoring case, newest first. Each carries the newest message that
     * matched as {@link Chat#getMatch()}, or none when only its name did.
     */
    public Page<Chat> searchByUserId(UUID userId, String query, Pageable pageable) {
        String searchQuery = searchQuery(query);
        log.info("Searching chats by user id {} page {} size {}", userId, pageable.getPageNumber(), pageable.getPageSize());
        log.debug("Chat search for user id {}: {}", userId, searchQuery);
        Page<Chat> chats = chatRepository.searchByUserId(userId, likePattern(searchQuery), SEARCHABLE_MESSAGE_TYPES, pageable);

        return withMatches(withMessages(chats), searchQuery);
    }

    /**
     * {@link #searchByUserId(UUID, String, Pageable)}, narrowed to the conversations not filed under
     * any group.
     */
    public Page<Chat> searchUngroupedByUserId(UUID userId, String query, Pageable pageable) {
        String searchQuery = searchQuery(query);
        log.info("Searching ungrouped chats by user id {} page {} size {}",
                userId, pageable.getPageNumber(), pageable.getPageSize());
        log.debug("Ungrouped chat search for user id {}: {}", userId, searchQuery);
        Page<Chat> chats = chatRepository.searchUngroupedByUserId(
                userId, likePattern(searchQuery), SEARCHABLE_MESSAGE_TYPES, pageable);

        return withMatches(withMessages(chats), searchQuery);
    }

    /**
     * Scoped exactly as {@link #rename(UUID, String)} is: without this, any authenticated caller
     * who knows or guesses another user's {@code chatId} could read that chat's full message
     * history. A chat owned by someone else is indistinguishable from one that does not exist.
     */
    public Chat get(UUID chatId) {
        UUID userId = userRequestContext.getUserId();
        Chat chat = ownedChat(chatId, userId);

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
     * them, the images generated inside it, and everything learned from it.
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
        Chat chat = chatRepository.lockByIdAndUserId(chatId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId));

        log.info("Deleting chat {} for user {}", chatId, userId);

        chatAttachmentService.deleteForChat(chatId);
        generatedImageService.deleteForChat(chatId);
        userMemoryService.forgetChat(chatId, userId);
        interestUpdateService.deleteForChat(chatId, userId);

        int messages = chatMessageRepository.deleteByChatId(chatId);

        chatRepository.delete(chat);

        log.info("Deleted chat {} and {} message(s) for user {}", chatId, messages, userId);

        discardTransientState(chatId, userId);
    }

    /**
     * Deletes a shared session's chat once everyone has left it. Not request-scoped: the last
     * participant to leave is not necessarily the owner, and the session service has already checked
     * that they were seated. A shared chat has no attachments, generated images, memories or news, so
     * only its messages, its row and its Redis state go; the session rows follow the chat by
     * {@code on delete cascade}.
     */
    @Transactional
    public void deleteSharedChat(UUID chatId) {
        chatRepository.lockById(chatId)
                .filter(chat -> chat.getKind() == ChatKind.SHARED)
                .ifPresent(chat -> {
                    int messages = chatMessageRepository.deleteByChatId(chatId);

                    chatRepository.delete(chat);

                    log.info("Deleted shared chat {} and {} message(s)", chatId, messages);

                    discardTransientState(chatId, chat.getUserId());
                });
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

    /**
     * Confirms the caller owns {@code chatId}, for a collection hanging off a conversation that has
     * no need of the conversation itself — {@code IngestedChatDocumentController}.
     * <p>
     * A {@code 404} rather than a {@code 403}, the same answer {@link #get(UUID)} gives and for the
     * same reason: a chat owned by someone else must be indistinguishable from one that does not
     * exist. Messages are deliberately not loaded — this asks a question about the chat rather than
     * reading it.
     *
     * @return the owner, which is the caller
     */
    public UUID requireOwned(UUID chatId) {
        UUID userId = userRequestContext.getUserId();
        ownedChat(chatId, userId);

        return userId;
    }

    private Chat ownedChat(UUID chatId, UUID userId) {
        return chatRepository.findByIdAndUserId(chatId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId));
    }

    /**
     * Drops the Redis state a deleted conversation leaves behind: any sticky A2A agent and task
     * bound to it, its stream buffer, and the model it was switched to.
     * <p>
     * Best effort, and deliberately not allowed to fail the delete. None of these keys means anything
     * without the chat row. All but the model selection expire on their own; that one has no TTL, so
     * this is the only thing that removes it, and a failure here leaves a few bytes keyed by a chat id
     * that can never be used again.
     */
    private void discardTransientState(UUID chatId, UUID userId) {
        a2aStickyAgentService.deactivate(chatId)
                .then(a2aStickyAgentService.deactivateTask(chatId))
                .then(redisStreamService.deleteStream(chatId, userId).then())
                .then(chatModelSelection.clear(chatId))
                .doOnError(error -> log.warn("Could not discard Redis state for deleted chat {}: {}",
                        chatId, error.getMessage()))
                .onErrorComplete()
                .subscribe();
    }

    private static String searchQuery(String query) {
        String searchQuery = StringUtils.trimToEmpty(query);

        if (searchQuery.length() < MIN_SEARCH_LENGTH || searchQuery.length() > MAX_SEARCH_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Search must be between " + MIN_SEARCH_LENGTH + " and " + MAX_SEARCH_LENGTH + " characters");
        }

        return searchQuery;
    }

    /**
     * The repository's {@code like} uses {@code \} as its escape character, so the query's own
     * wildcards and backslashes match themselves.
     */
    private static String likePattern(String searchQuery) {
        String escaped = searchQuery.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        return "%" + escaped + "%";
    }

    private static Page<Chat> withMatches(Page<Chat> chats, String searchQuery) {
        for (Chat chat : chats) {
            chat.setMatch(ChatSearchSnippets.match(chat.getChatMessages(), searchQuery).orElse(null));
        }

        return chats;
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

        Map<UUID, List<GeneratedImageSummary>> generatedImagesByMessageId = generatedImageService.forChat(chatId)
                .stream()
                .collect(Collectors.groupingBy(GeneratedImageSummary::chatMessageId));

        for (ChatMessage chatMessage : chatMessages) {
            chatMessage.setAttachments(attachmentsByMessageId.getOrDefault(chatMessage.getId(), List.of()));
            chatMessage.setGeneratedImages(generatedImagesByMessageId.getOrDefault(chatMessage.getId(), List.of()));
        }

        return chatMessages;
    }
}
