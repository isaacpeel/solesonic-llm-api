package com.solesonic.repository.session;

import com.solesonic.model.session.SessionInvite;
import com.solesonic.model.session.SessionInviteStatus;
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
}
