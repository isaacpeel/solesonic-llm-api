package com.solesonic.model.session;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A shared session as any participant sees it. {@code yourTurn} is answered for the caller on a REST
 * read and is null on the stream's {@code session_state} frame, which every participant receives:
 * they work it out from {@code turn.userIds}. {@code name} is the chat's name and {@code ownerUserId}
 * the user in seat 0; {@code agentName} is null in conversation mode. {@code model} is the chat's current
 * model in conversation mode, null for the server's default (and in agent mode), or {@link #MODEL_UNREAD}
 * when it could not be read, in which case it is left out of the JSON.
 */
public record SharedSessionSummary(UUID id,
                                   UUID chatId,
                                   SessionMode mode,
                                   String name,
                                   UUID ownerUserId,
                                   String agentName,
                                   SessionStatus status,
                                   @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = SharedSessionSummary.UnreadModel.class)
                                   String model,
                                   List<SessionParticipantSummary> participants,
                                   List<PendingInvitee> pendingInvitees,
                                   SessionTurn turn,
                                   SessionPromptView prompt,
                                   Boolean yourTurn,
                                   boolean runActive,
                                   ZonedDateTime created,
                                   ZonedDateTime updated) {

    /**
     * Stands in for a model that could not be read (Redis was unavailable): the {@code model} field is left out
     * of the JSON altogether, which a client reads as "not stated", where {@code null} says "the default". Never
     * a real model id.
     */
    public static final String MODEL_UNREAD = "\u0000unread";

    /**
     * Jackson's value filter for {@code model}: it excludes the value this returns {@code true} for.
     */
    public static final class UnreadModel {
        @Override
        public boolean equals(Object value) {
            return MODEL_UNREAD.equals(value);
        }

        @Override
        public int hashCode() {
            return MODEL_UNREAD.hashCode();
        }
    }

    /**
     * Someone the owner invited who has not answered yet.
     */
    public record PendingInvitee(UUID userId, String username, String displayName) {
    }
}
