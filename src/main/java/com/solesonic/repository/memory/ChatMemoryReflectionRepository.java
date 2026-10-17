package com.solesonic.repository.memory;

import com.solesonic.model.memory.ChatMemoryReflection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
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
     * Gives every conversation with messages a row to claim. On first deploy this is every existing
     * conversation, which is the whole of the retroactive backfill. The owner is copied from the chat
     * row, the only place it is recorded.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into chat_memory_reflection (chat_id, user_id, reflected_through, status, attempts, updated)
            select chat.id, chat.user_id, timestamp with time zone 'epoch', 'PENDING', 0, now()
              from chat
             where chat.user_id is not null
               and not exists (select 1 from chat_memory_reflection reflection where reflection.chat_id = chat.id)
               and exists (select 1 from chat_message message where message.chat_id = chat.id)
            on conflict (chat_id) do nothing
            """)
    int seedMissing();

    /**
     * Conversations with unreflected messages that have gone quiet, oldest unreflected message first,
     * so each user's history is learned in roughly the order it happened. {@code skip locked} keeps
     * two instances from claiming the same chat. A row left {@code IN_PROGRESS} by an instance that
     * died is claimable again once it is as old as the idle window. A {@code FAILED} chat gets one more
     * attempt each time the conversation carries on after the failure.
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
             order by (select min(message.timestamp)
                         from chat_message message
                        where message.chat_id = reflection.chat_id
                          and message.timestamp > reflection.reflected_through)
             limit :batchSize
               for update of reflection skip locked
            """)
    List<ChatMemoryReflection> findClaimable(ZonedDateTime idleBefore, int batchSize);
}
