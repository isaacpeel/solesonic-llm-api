package com.solesonic.service.session;

import com.solesonic.exception.session.SessionException;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SharedSession;
import com.solesonic.redis.service.RedisStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.solesonic.service.session.SessionFixtures.ALEX;
import static com.solesonic.service.session.SessionFixtures.CHAT_ID;
import static com.solesonic.service.session.SessionFixtures.KIM;
import static com.solesonic.service.session.SessionFixtures.SAM;
import static com.solesonic.service.session.SessionFixtures.SESSION_ID;
import static com.solesonic.service.session.SessionFixtures.conversationSession;
import static com.solesonic.service.session.SessionFixtures.participant;
import static com.solesonic.service.session.SessionFixtures.session;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionStreamServiceTest {
    private static final Duration RECHECK = Duration.ofMillis(20);

    @Mock
    private SharedSessionService sharedSessionService;
    @Mock
    private SessionRunLock sessionRunLock;
    @Mock
    private RedisStreamService redisStreamService;

    private SessionStreamService sessionStreamService;
    private final AtomicBoolean left = new AtomicBoolean();
    private SharedSession sharedSession;

    @BeforeEach
    void setUp() {
        sessionStreamService = new SessionStreamService(sharedSessionService, sessionRunLock, redisStreamService, RECHECK);

        sharedSession = conversationSession(SessionStatus.ACTIVE);

        when(sharedSessionService.requireParticipant(SESSION_ID, KIM)).thenReturn(sharedSession);
        when(sharedSessionService.participants(SESSION_ID)).thenReturn(List.of(participant(ALEX, 0, "Alex"), participant(KIM, 1, "Kim")));
        when(sessionRunLock.cursor(SESSION_ID)).thenReturn(Optional.of("1-0"));
        when(redisStreamService.follow(any(), any(), any(), anyString())).thenReturn(Flux.never());
        when(sharedSessionService.requireSeated(SESSION_ID, KIM)).thenAnswer(invocation -> {
            if (left.get()) {
                throw new SessionException(SessionErrorCode.SESSION_NOT_FOUND, "Session not found");
            }
            return sharedSession;
        });
    }

    private Flux<ServerSentEvent<?>> watch() {
        ResponseEntity<Flux<ServerSentEvent<?>>> response = sessionStreamService.watch(SESSION_ID, KIM, null);
        return response.getBody();
    }

    @Test
    void aConversationStreamEndsOnceItsReaderHasLeft() {
        StepVerifier.create(watch())
                .expectSubscription()
                .then(() -> left.set(true))
                .expectComplete()
                .verify(Duration.ofSeconds(3));
    }

    @Test
    void aConversationStreamEndsOnceTheSessionIsNoLongerActive() {
        StepVerifier.create(watch())
                .expectSubscription()
                .then(() -> sharedSession.setStatus(SessionStatus.CLOSED))
                .expectComplete()
                .verify(Duration.ofSeconds(3));
    }

    @Test
    void aStreamOpenedOnAnAlreadyClosedConversationIsNotEndedForThat() {
        sharedSession.setStatus(SessionStatus.CLOSED);

        StepVerifier.create(watch())
                .expectSubscription()
                .expectNoEvent(RECHECK.multipliedBy(6))
                .thenCancel()
                .verify();
    }

    @Test
    void aStreamKeepsGoingWhileItsReaderIsSeated() {
        StepVerifier.create(watch())
                .expectSubscription()
                .expectNoEvent(RECHECK.multipliedBy(6))
                .thenCancel()
                .verify();
    }

    @Test
    void anAgentSessionStreamIsNotRechecked() {
        SharedSession agent = session(SessionStatus.ACTIVE);
        when(sharedSessionService.requireParticipant(SESSION_ID, KIM)).thenReturn(agent);
        left.set(true);

        StepVerifier.create(watch())
                .expectSubscription()
                .expectNoEvent(RECHECK.multipliedBy(6))
                .thenCancel()
                .verify();
    }
}
