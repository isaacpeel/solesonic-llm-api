package com.solesonic.repository.memory;

import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method is scoped by {@code userId}, so another user's memory is indistinguishable from one
 * that does not exist. Nothing in the memory code calls {@code findById}.
 */
public interface UserMemoryRepository extends JpaRepository<UserMemory, UUID> {

    Optional<UserMemory> findByIdAndUserId(UUID id, UUID userId);

    Optional<UserMemory> findByIdAndUserIdAndStatusIn(UUID id, UUID userId, Collection<MemoryStatus> statuses);

    List<UserMemory> findByUserIdAndStatusInOrderByCreatedAsc(UUID userId, Collection<MemoryStatus> statuses);

    List<UserMemory> findByUserIdAndSupersedesIn(UUID userId, Collection<UUID> supersedes);

    Optional<UserMemory> findByUserIdAndMemoryKeyAndStatus(UUID userId, String memoryKey, MemoryStatus status);

    /**
     * The memories among {@code memoryIds} left with no evidence at all. {@code excludedOrigin} is
     * always {@link MemoryOrigin#AUTHORED}: the user wrote those directly, so they never had any.
     */
    @Query("""
            select memory
              from UserMemory memory
             where memory.userId = :userId
               and memory.id in :memoryIds
               and memory.origin <> :excludedOrigin
               and not exists (select evidence
                                 from UserMemoryEvidence evidence
                                where evidence.userId = :userId
                                  and evidence.memoryId = memory.id)
           """)
    List<UserMemory> findUnevidenced(UUID userId, Collection<UUID> memoryIds, MemoryOrigin excludedOrigin);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from UserMemory memory
             where memory.userId = :userId
               and memory.id in :memoryIds
           """)
    void deleteByUserIdAndIdIn(UUID userId, Collection<UUID> memoryIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from UserMemory memory
             where memory.userId = :userId
           """)
    int deleteAllByUserId(UUID userId);
}
