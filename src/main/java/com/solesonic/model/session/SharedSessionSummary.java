package com.solesonic.model.session;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A shared session as any participant sees it. {@code yourTurn} is answered for the caller on a REST
 * read and is null on the stream's {@code session_state} frame, which every participant receives:
 * they work it out from {@code turn.userIds}.
 */
public record SharedSessionSummary(UUID id,
                                   UUID chatId,
                                   String agentName,
                                   SessionStatus status,
                                   List<SessionParticipantSummary> participants,
                                   SessionTurn turn,
                                   SessionPromptView prompt,
                                   Boolean yourTurn,
                                   boolean runActive,
                                   ZonedDateTime created,
                                   ZonedDateTime updated) {
}
