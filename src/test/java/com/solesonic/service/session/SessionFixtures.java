package com.solesonic.service.session;

import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SharedSession;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One shared session: Alex invited Sam, so Alex owns the chat and sits in seat 0.
 */
final class SessionFixtures {
    static final UUID SESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID CHAT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID ALEX = UUID.fromString("10000000-0000-0000-0000-00000000000a");
    static final UUID SAM = UUID.fromString("10000000-0000-0000-0000-00000000000b");
    static final UUID STRANGER = UUID.fromString("10000000-0000-0000-0000-00000000000c");
    static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-26T12:00:00Z");
    static final UUID KIM = UUID.fromString("10000000-0000-0000-0000-00000000000d");
    static final String AGENT = "feud";

    static final SharedSessionSettings SETTINGS = new SharedSessionSettings(Set.of(AGENT), Duration.ofHours(24), 5, 20, 40,
            Duration.ofSeconds(600), Duration.ofDays(7));

    /**
     * Conversation mode: at most four people, 50-character messages, the last three messages forked.
     */
    static final SharedSessionSettings CONVERSATION_SETTINGS = settings(4, 5);

    private SessionFixtures() {
    }

    static SharedSessionSettings settings(int maxParticipants, int maxPendingInvites) {
        return new SharedSessionSettings(Set.of(AGENT), Duration.ofHours(24), maxPendingInvites, 20, 40,
                Duration.ofSeconds(600), Duration.ofDays(7),
                new SharedSessionSettings.Conversation(true, maxParticipants, 50, Duration.ofSeconds(2100),
                        Set.of("web_search"), 3));
    }

    static SharedSession conversationSession(SessionStatus status) {
        SharedSession session = session(status);
        session.setMode(SessionMode.CONVERSATION);
        session.setAgentName(null);
        return session;
    }

    static SharedSession session(SessionStatus status) {
        SharedSession session = new SharedSession();
        session.setId(SESSION_ID);
        session.setChatId(CHAT_ID);
        session.setMode(SessionMode.AGENT);
        session.setAgentName(AGENT);
        session.setStatus(status);
        session.setCreated(NOW);
        session.setUpdated(NOW);
        return session;
    }

    static List<SessionParticipant> participants() {
        return List.of(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"));
    }

    static SessionParticipant participant(UUID userId, int seat, String name) {
        SessionParticipant participant = new SessionParticipant();
        participant.setId(UUID.randomUUID());
        participant.setSessionId(SESSION_ID);
        participant.setUserId(userId);
        participant.setSeat(seat);
        participant.setName(name);
        participant.setJoined(NOW);
        return participant;
    }
}
