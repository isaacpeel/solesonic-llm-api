package com.solesonic.model.session;

import java.time.ZonedDateTime;
import java.util.UUID;

public record SessionInviteSummary(UUID id,
                                   UUID inviterUserId,
                                   String inviterUsername,
                                   String inviterDisplayName,
                                   UUID inviteeUserId,
                                   String inviteeUsername,
                                   String inviteeDisplayName,
                                   String agentName,
                                   String openingMessage,
                                   SessionInviteStatus status,
                                   UUID sessionId,
                                   ZonedDateTime created,
                                   ZonedDateTime expires) {
}
