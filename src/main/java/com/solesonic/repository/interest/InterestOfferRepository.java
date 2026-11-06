package com.solesonic.repository.interest;

import com.solesonic.model.interest.InterestOffer;
import com.solesonic.model.interest.OfferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method but the two sweeps is scoped by {@code userId}, so another user's offer is
 * indistinguishable from one that does not exist.
 */
public interface InterestOfferRepository extends JpaRepository<InterestOffer, UUID> {

    Optional<InterestOffer> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select offer from InterestOffer offer where offer.id = :id and offer.userId = :userId")
    Optional<InterestOffer> lockByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndStatus(UUID userId, OfferStatus status);

    List<InterestOffer> findByUserIdAndStatusAndExpiresAfterOrderByCreatedDesc(UUID userId, OfferStatus status, ZonedDateTime expiresAfter);

    /**
     * The topics discovery must not ask about again: one already waiting for an answer, or one the
     * user declined after {@code declinedAfter}.
     */
    @Query(nativeQuery = true, value = """
            select distinct offer.topic_key
              from interest_offer offer
             where offer.user_id = :userId
               and (offer.status = 'PENDING'
                    or (offer.status = 'DECLINED' and offer.decided > :declinedAfter))
            """)
    List<String> findBlockedTopicKeys(UUID userId, ZonedDateTime declinedAfter);

    /**
     * Withdraws this user's pending offers none of whose source memories is still live. Called inside
     * every transaction that forgets memories, so deleting a conversation also takes back the
     * question it prompted. The persistence context is flushed so the forget's deletes are visible,
     * but deliberately not cleared: the caller's transaction carries on with its own entities.
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            update interest_offer offer
               set status = 'WITHDRAWN', decided = :now
             where offer.user_id = :userId
               and offer.status = 'PENDING'
               and not exists (select 1
                                 from user_memory memory
                                where memory.user_id = :userId
                                  and memory.id = any (offer.source_memory_ids)
                                  and memory.status in ('ACTIVE', 'PENDING'))
            """)
    int withdrawOrphaned(UUID userId, ZonedDateTime now);

    /**
     * The same for every user: memories also stop being live by supersession and by eviction at the
     * cap, which call no hook, so the scheduled sweep catches those.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update interest_offer offer
               set status = 'WITHDRAWN', decided = :now
             where offer.status = 'PENDING'
               and not exists (select 1
                                 from user_memory memory
                                where memory.user_id = offer.user_id
                                  and memory.id = any (offer.source_memory_ids)
                                  and memory.status in ('ACTIVE', 'PENDING'))
            """)
    int withdrawAllOrphaned(ZonedDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            update interest_offer offer
               set status = 'EXPIRED', decided = :now
             where offer.status = 'PENDING'
               and offer.expires <= :now
            """)
    int expireDue(ZonedDateTime now);
}
