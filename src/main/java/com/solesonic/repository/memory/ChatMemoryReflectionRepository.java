package com.solesonic.repository.memory;

import com.solesonic.model.memory.ChatMemoryReflection;
import com.solesonic.model.memory.ReflectionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMemoryReflectionRepository extends JpaRepository<ChatMemoryReflection, UUID> {

    /**
     * Locks the row so a reflection applying its result and a delete forgetting the same chat can
     * never interleave: whichever takes the lock second sees what the first did.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChatMemoryReflection> findByChatIdAndUserId(UUID chatId, UUID userId);

    /**
     * Gives every conversation with messages a row to claim, for owners who are still around: not
     * removed from Keycloak, and with a {@code USER} message since {@code activeSince}. A returning
     * dormant user has their whole history seeded again. The owner is copied from the chat row, the
     * only place it is recorded.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into chat_memory_reflection (chat_id, user_id, reflected_through, status, attempts, updated)
            select chat.id, chat.user_id, timestamp with time zone 'epoch', 'PENDING', 0, now()
              from chat
             where chat.user_id is not null
               and not exists (select 1 from chat_memory_reflection reflection where reflection.chat_id = chat.id)
               and exists (select 1 from chat_message message where message.chat_id = chat.id)
               and exists (select 1
                             from chat recent
                             join chat_message message on message.chat_id = recent.id
                            where recent.user_id = chat.user_id
                              and message.message_type = 'USER'
                              and message.timestamp >= :activeSince)
               and not exists (select 1
                                 from user_profile profile
                                where profile.user_id = chat.user_id
                                  and profile.removed is not null)
            on conflict (chat_id) do nothing
            """)
    int seedMissing(ZonedDateTime activeSince);

    /**
     * Conversations with unreflected messages that have gone quiet, oldest unreflected message first,
     * so each user's history is learned in roughly the order it happened. {@code skip locked} keeps
     * two instances from claiming the same chat. A row left {@code IN_PROGRESS} by an instance that
     * died is claimable again once it is as old as the idle window. A {@code FAILED} chat gets one more
     * attempt each time the conversation carries on after the failure. Owners who are removed or
     * dormant are skipped, as in {@link #seedMissing}, until the lifecycle sweep purges their rows.
     */
    @Query(nativeQuery = true, value = """
            select reflection.*
              from chat_memory_reflection reflection
             where (reflection.status in ('PENDING', 'COMPLETED')
                    or (reflection.status = 'IN_PROGRESS' and reflection.updated < :idleBefore)
                    or (reflection.status = 'FAILED'
                        and exists (select 1
                                      from chat_message message
                                     where message.chat_id = reflection.chat_id
                                       and message.timestamp > reflection.updated)))
               and exists (select 1
                             from chat_message message
                            where message.chat_id = reflection.chat_id
                              and message.timestamp > reflection.reflected_through)
               and not exists (select 1
                                 from chat_message message
                                where message.chat_id = reflection.chat_id
                                  and message.timestamp >= :idleBefore)
               and exists (select 1
                             from chat recent
                             join chat_message message on message.chat_id = recent.id
                            where recent.user_id = reflection.user_id
                              and message.message_type = 'USER'
                              and message.timestamp >= :activeSince)
               and not exists (select 1
                                 from user_profile profile
                                where profile.user_id = reflection.user_id
                                  and profile.removed is not null)
             order by (select min(message.timestamp)
                         from chat_message message
                        where message.chat_id = reflection.chat_id
                          and message.timestamp > reflection.reflected_through)
             limit :batchSize
               for update of reflection skip locked
            """)
    List<ChatMemoryReflection> findClaimable(ZonedDateTime idleBefore, ZonedDateTime activeSince, int batchSize);

    /**
     * Every user's reflection rows, for the admin status page only.
     */
    Page<ChatMemoryReflection> findByStatusIn(Collection<ReflectionStatus> statuses, Pageable pageable);

    Page<ChatMemoryReflection> findByUserIdAndStatusIn(UUID userId, Collection<ReflectionStatus> statuses, Pageable pageable);
}
