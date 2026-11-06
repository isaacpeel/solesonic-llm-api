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
    //
    //The history lists show the caller's own CONVERSATION chats plus the shared conversations they are still
    //seated in. Those are passed in as ids (SharedSessionRepository.findVisibleConversationChatIds, which is
    //an index lookup on session_participant) rather than tested with an exists() here: an "or exists" would
    //leave Postgres no index to use for the second branch, whereas "user_id = ? or id in (...)" is a
    //BitmapOr of chat_user_timestamp_idx and the primary key. An owner's hand-placed position means nothing
    //to anyone else, so only the owner's rows are ordered by it.
    @Query("""
            from Chat chat
            where (chat.userId = :userId and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION)
               or chat.id in :sharedChatIds
            order by case when chat.userId = :userId then chat.sortOrder end asc nulls last, chat.timestamp desc, chat.id desc
            """)
    Page<Chat> findByUserId(UUID userId, Collection<UUID> sharedChatIds, Pageable pageable);

    // The same window and the same deterministic ordering as findByUserId, narrowed to one group
    // and read off that group's own position column. Still filtered by user rather than by group
    // alone: the group has already been checked against the caller, and repeating the check at the
    // query is what keeps a chat that was filed under a group and later reassigned from ever
    // leaking through a stale group id.
    @Query("""
            from Chat chat
            where chat.userId = :userId and chat.chatGroupId = :chatGroupId
              and (chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION or chat.id in :sharedChatIds)
            order by chat.groupSortOrder asc nulls last, chat.timestamp desc, chat.id desc
            """)
    Page<Chat> findByUserIdAndChatGroupId(UUID userId, UUID chatGroupId, Collection<UUID> sharedChatIds, Pageable pageable);

    /**
     * The hand-placed prefix of the user's list, in the order it is rendered — the arrangement a
     * move is applied to. Unplaced chats are deliberately excluded: they have no position to
     * renumber, and loading a user's entire history to move one conversation would make the cost of
     * a drag grow with the size of the sidebar.
     */
    @Query("""
            from Chat chat
            where chat.userId = :userId and chat.sortOrder is not null
              and (chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION or chat.id in :sharedChatIds)
            order by chat.sortOrder asc, chat.id asc
            """)
    List<Chat> findPlacedByUserId(UUID userId, Collection<UUID> sharedChatIds);

    /**
     * The same arrangement, within one group.
     */
    @Query("""
            from Chat chat
            where chat.userId = :userId
              and chat.chatGroupId = :chatGroupId
              and chat.groupSortOrder is not null
              and (chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION or chat.id in :sharedChatIds)
            order by chat.groupSortOrder asc, chat.id asc
            """)
    List<Chat> findPlacedByUserIdAndChatGroupId(UUID userId, UUID chatGroupId, Collection<UUID> sharedChatIds);

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
            where (chat.userId = :userId and chat.chatGroupId is null
                   and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION)
               or (chat.id in :sharedChatIds and (chat.userId <> :userId or chat.chatGroupId is null))
            order by case when chat.userId = :userId then chat.sortOrder end asc nulls last, chat.timestamp desc, chat.id desc
            """)
    Page<Chat> findUngroupedByUserId(UUID userId, Collection<UUID> sharedChatIds, Pageable pageable);

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
            where ((chat.userId = :userId and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION)
                   or chat.id in :sharedChatIds)
              and (lower(chat.name) like :pattern escape '\\'
                   or exists (select 1 from ChatMessage chatMessage
                              where chatMessage.chatId = chat.id
                                and chatMessage.messageType in :messageTypes
                                and lower(chatMessage.message) like :pattern escape '\\'))
            order by chat.timestamp desc, chat.id desc
            """)
    Page<Chat> searchByUserId(UUID userId, Collection<UUID> sharedChatIds, String pattern, Collection<MessageType> messageTypes, Pageable pageable);

    /**
     * {@link #searchByUserId}, narrowed to the conversations not filed under any group.
     */
    @Query("""
            from Chat chat
            where ((chat.userId = :userId and chat.chatGroupId is null
                    and chat.kind = com.solesonic.model.chat.history.ChatKind.CONVERSATION)
                   or (chat.id in :sharedChatIds and (chat.userId <> :userId or chat.chatGroupId is null)))
              and (lower(chat.name) like :pattern escape '\\'
                   or exists (select 1 from ChatMessage chatMessage
                              where chatMessage.chatId = chat.id
                                and chatMessage.messageType in :messageTypes
                                and lower(chatMessage.message) like :pattern escape '\\'))
            order by chat.timestamp desc, chat.id desc
            """)
    Page<Chat> searchUngroupedByUserId(UUID userId, Collection<UUID> sharedChatIds, String pattern, Collection<MessageType> messageTypes, Pageable pageable);

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

    /**
     * A shared conversation as one of its seated participants sees it: the session is a conversation that
     * still takes messages and the caller has not left it. Whoever else it belongs to is for the caller to
     * check ({@code chat.userId} is the owner). Anyone who left, never joined, or whose session is an agent
     * session or is over gets nothing, which is what keeps those chats out of history, rename and the model
     * endpoints alike.
     */
    @Query("""
            from Chat chat
            where chat.id = :chatId
              and chat.kind = com.solesonic.model.chat.history.ChatKind.SHARED
              and exists (select 1 from SharedSession sharedSession, SessionParticipant participant
                          where sharedSession.chatId = chat.id
                            and sharedSession.mode = com.solesonic.model.session.SessionMode.CONVERSATION
                            and sharedSession.status in (com.solesonic.model.session.SessionStatus.ACTIVE,
                                                         com.solesonic.model.session.SessionStatus.COMPLETED)
                            and participant.sessionId = sharedSession.id
                            and participant.userId = :userId
                            and participant.leftAt is null)
            """)
    Optional<Chat> findVisibleSharedById(UUID chatId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            from Chat chat
            where chat.id = :chatId
            """)
    Optional<Chat> lockById(UUID chatId);
}
