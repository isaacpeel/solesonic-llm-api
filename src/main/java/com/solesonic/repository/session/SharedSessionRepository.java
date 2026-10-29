package com.solesonic.repository.session;

import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SharedSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Every read a participant makes is scoped by a participant join inside the query, so a session the
 * caller does not take part in is a {@code 404} and there is no path on which the check can be skipped.
 */
public interface SharedSessionRepository extends JpaRepository<SharedSession, UUID> {

    @Query("""
            from SharedSession sharedSession
            where sharedSession.id = :sessionId
              and exists (select 1 from SessionParticipant participant
                          where participant.sessionId = sharedSession.id and participant.userId = :userId)
            """)
    Optional<SharedSession> findForParticipant(UUID sessionId, UUID userId);

    /**
     * {@link #findForParticipant} for a participant who has not left: someone who left can read the
     * session they left, but never write to it again.
     */
    @Query("""
            from SharedSession sharedSession
            where sharedSession.id = :sessionId
              and exists (select 1 from SessionParticipant participant
                          where participant.sessionId = sharedSession.id
                            and participant.userId = :userId
                            and participant.leftAt is null)
            """)
    Optional<SharedSession> findForSeatedParticipant(UUID sessionId, UUID userId);

    @Query("""
            from SharedSession sharedSession
            where sharedSession.status in :statuses
              and exists (select 1 from SessionParticipant participant
                          where participant.sessionId = sharedSession.id and participant.userId = :userId)
            order by sharedSession.updated desc, sharedSession.id desc
            """)
    Page<SharedSession> findAllForParticipant(UUID userId, Collection<SessionStatus> statuses, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            from SharedSession sharedSession
            where sharedSession.id = :sessionId
            """)
    Optional<SharedSession> lockById(UUID sessionId);

    /**
     * Sessions nobody has written to since {@code before}. Set to match the agent's own expiry of its
     * state, so a session is not left accepting messages for state the agent has already dropped.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SharedSession sharedSession
            set sharedSession.status = com.solesonic.model.session.SessionStatus.EXPIRED, sharedSession.updated = :now
            where sharedSession.status in (com.solesonic.model.session.SessionStatus.ACTIVE, com.solesonic.model.session.SessionStatus.COMPLETED)
              and sharedSession.updated < :before
            """)
    int expireIdle(ZonedDateTime before, ZonedDateTime now);
}
