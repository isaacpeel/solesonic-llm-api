package com.solesonic.repository.interest;

import com.solesonic.model.interest.InterestScan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterestScanRepository extends JpaRepository<InterestScan, UUID> {

    /**
     * Locks the row so a discovery applying its result serializes with another instance that
     * re-claimed the same user after a slow model call.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select scan from InterestScan scan where scan.userId = :userId")
    Optional<InterestScan> lockByUserId(UUID userId);

    /**
     * Gives every user with an active fact or preference a row to claim. On first deploy that is
     * every user reflection has already taught something.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into interest_scan (user_id, scanned_through, last_offered, status, attempts, updated)
            select distinct memory.user_id, cast(null as timestamptz), cast(null as timestamptz), 'PENDING', 0, now()
              from user_memory memory
             where memory.status = 'ACTIVE'
               and memory.kind in ('FACT', 'PREFERENCE')
               and not exists (select 1 from interest_scan scan where scan.user_id = memory.user_id)
            on conflict (user_id) do nothing
            """)
    int seedMissing();

    /**
     * Users reflection has taught something since discovery last read them, who have no question
     * waiting, are past the offer cooldown and below the agent limit — the ones not yet read first.
     * {@code skip locked} keeps two instances from claiming the same user. A row left
     * {@code IN_PROGRESS} by an instance that died is claimable again once it is older than
     * {@code staleBefore}; a {@code FAILED} one only once the user's memories change after the failure.
     */
    @Query(nativeQuery = true, value = """
            select scan.*
              from interest_scan scan
             where (scan.status in ('PENDING', 'COMPLETED')
                    or (scan.status = 'IN_PROGRESS' and scan.updated < :staleBefore)
                    or (scan.status = 'FAILED'
                        and exists (select 1
                                      from user_memory memory
                                     where memory.user_id = scan.user_id
                                       and memory.status = 'ACTIVE'
                                       and memory.kind in ('FACT', 'PREFERENCE')
                                       and memory.updated > scan.updated)))
               and exists (select 1
                             from user_memory memory
                            where memory.user_id = scan.user_id
                              and memory.status = 'ACTIVE'
                              and memory.kind in ('FACT', 'PREFERENCE')
                              and (scan.scanned_through is null or memory.updated > scan.scanned_through))
               and not exists (select 1
                                 from interest_offer offer
                                where offer.user_id = scan.user_id
                                  and offer.status = 'PENDING')
               and (scan.last_offered is null or scan.last_offered < :offerCooldownBefore)
               and (select count(*) from interest_agent agent where agent.user_id = scan.user_id) < :maxAgents
             order by scan.scanned_through nulls first
             limit :batchSize
               for update of scan skip locked
            """)
    List<InterestScan> findClaimable(ZonedDateTime staleBefore, ZonedDateTime offerCooldownBefore, int maxAgents, int batchSize);
}
