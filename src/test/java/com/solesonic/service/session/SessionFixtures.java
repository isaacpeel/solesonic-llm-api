package com.solesonic.service.session;

import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SharedSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

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
    static final String AGENT = "feud";

    static final SharedSessionSettings SETTINGS = new SharedSessionSettings(Set.of(AGENT), Duration.ofHours(24), 5, 20, 40,
            Duration.ofSeconds(600), Duration.ofDays(7));

    private SessionFixtures() {
    }

    static SharedSession session(SessionStatus status) {
        SharedSession session = new SharedSession();
        session.setId(SESSION_ID);
        session.setChatId(CHAT_ID);
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

    static Authentication authentication() {
        Jwt jwt = Jwt.withTokenValue("user-jwt").header("alg", "none").subject(SAM.toString()).build();

        return new TestingAuthenticationToken(jwt, null);
    }
}
