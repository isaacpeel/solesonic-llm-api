package com.solesonic.repository.chat;

import com.solesonic.model.chat.history.Chat;
import jakarta.persistence.LockModeType;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRepository extends JpaRepository<Chat, UUID> {
    // Hand-placed conversations first, then everything else newest first, with the id as a
    // tiebreaker. Chats created in the same millisecond would otherwise be ordered arbitrarily per
    // query, which an infinite scroll sees as a row that repeats on one page and never appears on
    // the next. The order lives here rather than in a caller-supplied Sort so that no caller can
    // page this without a deterministic ordering.
    //
    // "nulls last" is what makes an unplaced chat keep its old behaviour: a conversation nobody has
    // arranged sorts by timestamp exactly as it did before ordering existed, and a brand new one
    // still arrives at the top of that section rather than at the bottom of the list.
    //Every read here asks for CONVERSATION: a shared chat is reached only through /shared-sessions, so
    //to every path in this repository it does not exist.
    @Query("""
            from Chat chat
            where chat.userId = :userId and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION
            order by chat.sortOrder asc nulls last, chat.timestamp desc, chat.id desc
            """)
    Page<Chat> findByUserId(UUID userId, Pageable pageable);

    // The same window and the same deterministic ordering as findByUserId, narrowed to one group
    // and read off that group's own position column. Still filtered by user rather than by group
    // alone: the group has already been checked against the caller, and repeating the check at the
    // query is what keeps a chat that was filed under a group and later reassigned from ever
    // leaking through a stale group id.
    @Query("""
            from Chat chat
            where chat.userId = :userId and chat.chatGroupId = :chatGroupId
              and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION
            order by chat.groupSortOrder asc nulls last, chat.timestamp desc, chat.id desc
            """)
    Page<Chat> findByUserIdAndChatGroupId(UUID userId, UUID chatGroupId, Pageable pageable);

    /**
     * The hand-placed prefix of the user's list, in the order it is rendered — the arrangement a
     * move is applied to. Unplaced chats are deliberately excluded: they have no position to
     * renumber, and loading a user's entire history to move one conversation would make the cost of
     * a drag grow with the size of the sidebar.
     */
    @Query("""
            from Chat chat
            where chat.userId = :userId and chat.sortOrder is not null
              and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION
            order by chat.sortOrder asc, chat.id asc
            """)
    List<Chat> findPlacedByUserId(UUID userId);

    /**
     * The same arrangement, within one group.
     */
    @Query("""
            from Chat chat
            where chat.userId = :userId
              and chat.chatGroupId = :chatGroupId
              and chat.groupSortOrder is not null
            order by chat.groupSortOrder asc, chat.id asc
            """)
    List<Chat> findPlacedByUserIdAndChatGroupId(UUID userId, UUID chatGroupId);

    /**
     * The same window and the same deterministic ordering as {@link #findByUserId}, narrowed to the
     * conversations that are not filed under any group.
     * <p>
     * It exists so a client rendering group sections above the main list does not have to filter
     * them out itself — which it can only do after the page has been counted, leaving the page
     * metadata describing more rows than the client will render, and infinite scroll stalling
     * whenever a whole page filters away to nothing.
     */
    @Query("""
            from Chat chat
            where chat.userId = :userId and chat.chatGroupId is null
              and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION
            order by chat.sortOrder asc nulls last, chat.timestamp desc, chat.id desc
            """)
    Page<Chat> findUngroupedByUserId(UUID userId, Pageable pageable);

    /**
     * The user's conversations, grouped or not, whose name or any message of {@code messageTypes}
     * matches {@code pattern} — an already lowercased and escaped {@code like} pattern.
     * <p>
     * Ordered by time rather than by hand-placed position: results are a timeline the client groups
     * by day as pages arrive. The filter is in the query, never applied afterwards, so the page
     * metadata counts exactly the rows returned.
     */
    @Query("""
            from Chat chat
            where chat.userId = :userId
              and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION
              and (lower(chat.name) like :pattern escape '\\'
                   or exists (select 1 from ChatMessage chatMessage
                              where chatMessage.chatId = chat.id
                                and chatMessage.messageType in :messageTypes
                                and lower(chatMessage.message) like :pattern escape '\\'))
            order by chat.timestamp desc, chat.id desc
            """)
    Page<Chat> searchByUserId(UUID userId, String pattern, Collection<MessageType> messageTypes, Pageable pageable);

    /**
     * {@link #searchByUserId}, narrowed to the conversations not filed under any group.
     */
    @Query("""
            from Chat chat
            where chat.userId = :userId and chat.chatGroupId is null
              and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION
              and (lower(chat.name) like :pattern escape '\\'
                   or exists (select 1 from ChatMessage chatMessage
                              where chatMessage.chatId = chat.id
                                and chatMessage.messageType in :messageTypes
                                and lower(chatMessage.message) like :pattern escape '\\'))
            order by chat.timestamp desc, chat.id desc
            """)
    Page<Chat> searchUngroupedByUserId(UUID userId, String pattern, Collection<MessageType> messageTypes, Pageable pageable);

    /**
     * Ungroups every conversation filed under one group, in one statement.
     * <p>
     * Both positions are cleared for the same reason {@code ChatGroupService.removeChat} clears
     * {@code groupSortOrder}: a place inside a group that no longer exists describes nothing.
     * {@code sortOrder} is deliberately left alone — that is the chat's place in the user's whole
     * list, which a deleted group says nothing about.
     * <p>
     * A bulk update rather than a read-modify-save, because a group holds an unbounded number of
     * conversations and none of them has to be read in order to be ungrouped. The database would
     * null the group id on its own — {@code chat_chat_group_id_fkey} is {@code on delete set null} —
     * but that leaves the position behind and Hibernate never learns it happened, so it is done
     * here instead of being left to the constraint.
     * <p>
     * User-scoped as well as group-scoped, so the statement cannot reach a row the caller does not
     * own even if a group id were ever resolved without an ownership check.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Chat chat
            set chat.chatGroupId = null, chat.groupSortOrder = null
            where chat.userId = :userId and chat.chatGroupId = :chatGroupId
            """)
    int clearChatGroup(UUID userId, UUID chatGroupId);

    /**
     * User-scoped for the same reason generated image lookups are: a caller must own the chat it
     * is modifying, and this is what lets a write be rejected at the query rather than trusted from
     * an id alone. A shared chat is never one: its owner reaches it only through {@code /shared-sessions}.
     */
    @Query("""
            from Chat chat
            where chat.id = :chatId and chat.userId = :userId
              and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION
            """)
    Optional<Chat> findByIdAndUserId(UUID chatId, UUID userId);

    /**
     * {@link #findByIdAndUserId} holding the row for the rest of the transaction. Deleting a chat
     * takes it before forgetting what was learned from it, and {@code /remember} takes it before
     * writing a memory, so the two can never interleave: a memory is either written first and
     * forgotten with the chat, or finds the chat already gone.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            from Chat chat
            where chat.id = :chatId and chat.userId = :userId
              and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION
            """)
    Optional<Chat> lockByIdAndUserId(UUID chatId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            from Chat chat
            where chat.id = :chatId
            """)
    Optional<Chat> lockById(UUID chatId);
}
