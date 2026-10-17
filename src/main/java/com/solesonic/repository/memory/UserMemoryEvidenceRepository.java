package com.solesonic.repository.memory;

import com.solesonic.model.memory.UserMemoryEvidence;
import com.solesonic.model.memory.UserMemoryEvidenceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserMemoryEvidenceRepository extends JpaRepository<UserMemoryEvidence, UserMemoryEvidenceId> {

    List<UserMemoryEvidence> findByUserIdAndChatId(UUID userId, UUID chatId);

    List<UserMemoryEvidence> findByUserIdAndChatMessageId(UUID userId, UUID chatMessageId);

    List<UserMemoryEvidence> findByUserIdAndMemoryIdIn(UUID userId, Collection<UUID> memoryIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from UserMemoryEvidence evidence
             where evidence.userId = :userId
               and evidence.chatId = :chatId
           """)
    void deleteByUserIdAndChatId(UUID userId, UUID chatId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from UserMemoryEvidence evidence
             where evidence.userId = :userId
               and evidence.chatMessageId = :chatMessageId
           """)
    void deleteByUserIdAndChatMessageId(UUID userId, UUID chatMessageId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from UserMemoryEvidence evidence
             where evidence.userId = :userId
               and evidence.memoryId = :memoryId
           """)
    void deleteByUserIdAndMemoryId(UUID userId, UUID memoryId);
}
