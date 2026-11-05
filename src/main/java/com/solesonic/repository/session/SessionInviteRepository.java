package com.solesonic.repository.session;

import com.solesonic.model.session.SessionInvite;
import com.solesonic.model.session.SessionInviteStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scoped to the inviter or the invitee at the query, so an invite between two other users is a
 * {@code 404}.
 */
public interface SessionInviteRepository extends JpaRepository<SessionInvite, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            from SessionInvite invite
            where invite.id = :inviteId and invite.inviteeUserId = :inviteeUserId
            """)
    Optional<SessionInvite> lockForInvitee(UUID inviteId, UUID inviteeUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            from SessionInvite invite
            where invite.id = :inviteId and invite.inviterUserId = :inviterUserId
            """)
    Optional<SessionInvite> lockForInviter(UUID inviteId, UUID inviterUserId);

    @Query("""
            from SessionInvite invite
            where invite.id = :inviteId
              and (invite.inviterUserId = :userId or invite.inviteeUserId = :userId)
            """)
    Optional<SessionInvite> findForParticipant(UUID inviteId, UUID userId);

    long countByInviterUserIdAndStatus(UUID inviterUserId, SessionInviteStatus status);

    @Query("""
            select count(invite) > 0
            from SessionInvite invite
            where invite.status = com.solesonic.model.session.SessionInviteStatus.PENDING
              and ((invite.inviterUserId = :firstUserId and invite.inviteeUserId = :secondUserId)
                or (invite.inviterUserId = :secondUserId and invite.inviteeUserId = :firstUserId))
            """)
    boolean existsPendingBetween(UUID firstUserId, UUID secondUserId);

    List<SessionInvite> findByInviteeUserIdAndStatusOrderByCreatedDesc(UUID inviteeUserId, SessionInviteStatus status);

    List<SessionInvite> findByInviterUserIdAndStatusOrderByCreatedDesc(UUID inviterUserId, SessionInviteStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SessionInvite invite
            set invite.status = com.solesonic.model.session.SessionInviteStatus.EXPIRED, invite.decided = :now
            where invite.status = com.solesonic.model.session.SessionInviteStatus.PENDING
              and invite.expires < :now
            """)
    int expire(ZonedDateTime now);

    /**
     * The scalar only, so nothing is loaded into the persistence context: {@code accept} reads the
     * session an invite points at, locks that session, and only then locks and loads the invite.
     */
    @Query("select invite.sessionId from SessionInvite invite where invite.id = :inviteId and invite.inviteeUserId = :inviteeUserId")
    Optional<UUID> findSessionIdForInvitee(UUID inviteId, UUID inviteeUserId);

    /**
     * Past-due invites of one inviter, expired so they stop counting toward caps and stop occupying the
     * per-session unique index.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SessionInvite invite
            set invite.status = com.solesonic.model.session.SessionInviteStatus.EXPIRED, invite.decided = :now
            where invite.status = com.solesonic.model.session.SessionInviteStatus.PENDING
              and invite.inviterUserId = :inviterUserId
              and invite.expires < :now
            """)
    int expireDueFor(UUID inviterUserId, ZonedDateTime now);

    List<SessionInvite> findBySessionIdAndStatus(UUID sessionId, SessionInviteStatus status);

    List<SessionInvite> findBySessionIdInAndStatus(Collection<UUID> sessionIds, SessionInviteStatus status);

    /**
     * An invite into a session that is over can never be accepted, so none is left waiting.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SessionInvite invite
            set invite.status = com.solesonic.model.session.SessionInviteStatus.CANCELLED, invite.decided = :now
            where invite.status = com.solesonic.model.session.SessionInviteStatus.PENDING
              and invite.sessionId = :sessionId
            """)
    int cancelPendingForSession(UUID sessionId, ZonedDateTime now);

    /**
     * {@link #cancelPendingForSession} for every session that is closed or expired.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SessionInvite invite
            set invite.status = com.solesonic.model.session.SessionInviteStatus.CANCELLED, invite.decided = :now
            where invite.status = com.solesonic.model.session.SessionInviteStatus.PENDING
              and invite.sessionId in (select sharedSession.id from SharedSession sharedSession
                                       where sharedSession.status in (com.solesonic.model.session.SessionStatus.CLOSED,
                                                                      com.solesonic.model.session.SessionStatus.EXPIRED))
            """)
    int cancelPendingForOverSessions(ZonedDateTime now);
}
