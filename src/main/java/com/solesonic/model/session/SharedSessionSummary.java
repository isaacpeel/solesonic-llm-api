package com.solesonic.model.session;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A shared session as any participant sees it. {@code yourTurn} is answered for the caller on a REST
 * read and is null on the stream's {@code session_state} frame, which every participant receives:
 * they work it out from {@code turn.userIds}. {@code name} is the chat's name and {@code ownerUserId}
 * the user in seat 0; {@code agentName} is null in conversation mode.
 */
public record SharedSessionSummary(UUID id,
                                   UUID chatId,
                                   SessionMode mode,
                                   String name,
                                   UUID ownerUserId,
                                   String agentName,
                                   SessionStatus status,
                                   List<SessionParticipantSummary> participants,
                                   List<PendingInvitee> pendingInvitees,
                                   SessionTurn turn,
                                   SessionPromptView prompt,
                                   Boolean yourTurn,
                                   boolean runActive,
                                   ZonedDateTime created,
                                   ZonedDateTime updated) {

    /**
     * Someone the owner invited who has not answered yet.
     */
    public record PendingInvitee(UUID userId, String username, String displayName) {
    }
}
