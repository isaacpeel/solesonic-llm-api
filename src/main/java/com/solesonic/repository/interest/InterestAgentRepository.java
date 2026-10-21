package com.solesonic.repository.interest;

import com.solesonic.model.interest.InterestAgent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method but {@link #findDue} is scoped by {@code userId}. The claim returns the owner with the
 * row, which is the only place a run ever learns whose agent it is running.
 */
public interface InterestAgentRepository extends JpaRepository<InterestAgent, UUID> {

    Optional<InterestAgent> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select agent from InterestAgent agent where agent.id = :id and agent.userId = :userId")
    Optional<InterestAgent> lockByIdAndUserId(UUID id, UUID userId);

    List<InterestAgent> findByUserIdOrderByCreatedAsc(UUID userId);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndTopicKey(UUID userId, String topicKey);

    @Query("select agent.topicKey from InterestAgent agent where agent.userId = :userId")
    List<String> findTopicKeys(UUID userId);

    /**
     * Active agents whose run is due, earliest first. {@code skip locked} keeps two instances from
     * claiming the same agent.
     */
    @Query(nativeQuery = true, value = """
            select agent.*
              from interest_agent agent
             where agent.status = 'ACTIVE'
               and agent.next_run_at <= :now
             order by agent.next_run_at
             limit :batchSize
               for update of agent skip locked
            """)
    List<InterestAgent> findDue(ZonedDateTime now, int batchSize);
}
