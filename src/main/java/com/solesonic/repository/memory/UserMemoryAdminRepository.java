package com.solesonic.repository.memory;

import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryOrigin;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only unscoped reads of {@code user_memory}, kept apart from {@link UserMemoryRepository} so
 * that one's rule — every method scoped by {@code userId} — stays true. Only
 * {@code MemoryAdminService}, behind the {@code izzy-bot-admin} group, may use this, apart from
 * {@link #findUserIdsByOriginAndStatusIn}: owner ids only, for the retention sweep to visit one user
 * at a time through the scoped repository.
 */
public interface UserMemoryAdminRepository extends Repository<UserMemory, UUID> {

    @Query("""
            select distinct memory.userId
              from UserMemory memory
             where memory.origin = :origin
               and memory.status in :statuses
           """)
    List<UUID> findUserIdsByOriginAndStatusIn(MemoryOrigin origin, Collection<MemoryStatus> statuses);

    Optional<UserMemory> findById(UUID id);

    Page<UserMemory> findByStatusInAndKindIn(Collection<MemoryStatus> statuses, Collection<MemoryKind> kinds, Pageable pageable);

    Page<UserMemory> findByUserIdAndStatusInAndKindIn(UUID userId,
                                                      Collection<MemoryStatus> statuses,
                                                      Collection<MemoryKind> kinds,
                                                      Pageable pageable);
}
