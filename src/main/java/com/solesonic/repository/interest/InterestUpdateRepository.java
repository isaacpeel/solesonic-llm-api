package com.solesonic.repository.interest;

import com.solesonic.model.interest.InterestUpdate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Every method is scoped by {@code userId}.
 */
public interface InterestUpdateRepository extends JpaRepository<InterestUpdate, UUID> {

    Page<InterestUpdate> findByUserIdAndAgentIdOrderByCreatedDesc(UUID userId, UUID agentId, Pageable pageable);

    Optional<InterestUpdate> findByChatIdAndUserId(UUID chatId, UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from InterestUpdate interestUpdate where interestUpdate.chatId = :chatId and interestUpdate.userId = :userId")
    void deleteByChatIdAndUserId(UUID chatId, UUID userId);
}
