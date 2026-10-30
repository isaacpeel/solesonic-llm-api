package com.solesonic.service.session;

import com.solesonic.model.a2a.A2APrompt;
import com.solesonic.model.a2a.A2ATurnState;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SessionTurn;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.session.SessionParticipantRepository;
import com.solesonic.repository.session.SharedSessionRepository;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.redis.TurnOutcome;
import com.solesonic.service.user.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.RecordId;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.session.SessionFixtures.ALEX;
import static com.solesonic.service.session.SessionFixtures.CHAT_ID;
import static com.solesonic.service.session.SessionFixtures.NOW;
import static com.solesonic.service.session.SessionFixtures.SAM;
import static com.solesonic.service.session.SessionFixtures.SESSION_ID;
import static com.solesonic.service.session.SessionFixtures.SETTINGS;
import static com.solesonic.service.session.SessionFixtures.participants;
import static com.solesonic.service.session.SessionFixtures.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharedSessionServiceTest {
    private static final UUID RUN_ID = UUID.randomUUID();

    @Mock
    private SharedSessionRepository sharedSessionRepository;

    @Mock
    private SessionParticipantRepository sessionParticipantRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatService chatService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private A2AStickyAgentService a2aStickyAgentService;

    @Mock
    private RedisStreamService redisStreamService;

    @Mock
    private SessionRunLock sessionRunLock;

    private SharedSessionService sharedSessionService;
    private SharedSession sharedSession;
    private List<SessionParticipant> participants;

    @BeforeEach
    void setUp() {
        sharedSessionService = new SharedSessionService(sharedSessionRepository, sessionParticipantRepository, chatRepository,
                chatService, chatMessageService, userProfileService, a2aStickyAgentService, redisStreamService, sessionRunLock,
                SETTINGS, Clock.fixed(NOW.toInstant(), NOW.getZone()));

        sharedSession = session(SessionStatus.ACTIVE);
        participants = participants();

        when(sharedSessionRepository.lockById(SESSION_ID)).thenReturn(Optional.of(sharedSession));
        when(sharedSessionRepository.save(any(SharedSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionParticipantRepository.findBySessionIdOrderBySeatAsc(SESSION_ID)).thenReturn(participants);
        when(userProfileService.usernames(any())).thenReturn(Map.of());
        when(a2aStickyAgentService.deactivateTask(CHAT_ID)).thenReturn(Mono.empty());
        when(redisStreamService.publish(any(), any(), anyString(), any())).thenReturn(Mono.just(RecordId.of("1-0")));
    }

    private static A2APrompt playOrPass(List<String> forParticipants) {
        return new A2APrompt("Your call", Map.of("type", "object", "properties", Map.of("choice", Map.of("type", "string"))),
                Map.of("feud/prompt", "play_or_pass", "solesonic/forParticipants", forParticipants));
    }

    @Test
    void theTurnsNamesBecomeTheirParticipantsIds() {
        SessionTurnCollector collector = new SessionTurnCollector();
        collector.turn(new A2ATurnState(List.of("Sam"), "answer", false, List.of("Alex")));

        SharedSessionSummary summary = sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.COMPLETED, collector);

        assertThat(summary.turn()).isEqualTo(new SessionTurn(List.of(SAM), "answer", false, List.of("Alex")));
        assertThat(summary.prompt()).isNull();
        assertThat(sharedSession.getLastRunId()).isEqualTo(RUN_ID);
        assertThat(sharedSession.getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aTurnSavedBeforeInputCouldBeHiddenHidesNothing() {
        SessionTurn turn = JsonMapper.builder().build()
                .readValue("{\"userIds\":[\"" + SAM + "\"],\"next\":\"answer\",\"over\":false}", SessionTurn.class);

        assertThat(turn).isEqualTo(new SessionTurn(List.of(SAM), "answer", false, List.of()));
    }

    @Test
    void aQuestionIsForTheParticipantsItsFormNames() {
        SessionTurnCollector collector = new SessionTurnCollector();
        collector.turn(new A2ATurnState(List.of("Alex"), "choice", false, List.of()));
        collector.prompt("task-1", playOrPass(List.of("Alex")));

        SharedSessionSummary summary = sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.COMPLETED, collector);

        assertThat(summary.prompt()).isNotNull();
        assertThat(summary.prompt().forUserIds()).containsExactly(ALEX);
        assertThat(summary.prompt().message()).isEqualTo("Your call");
        assertThat(sharedSession.getPendingPrompt().taskId()).isEqualTo("task-1");
    }

    @Test
    void aQuestionThatNamesNobodyIsForWhoeverTheTurnWaitsOn() {
        SessionTurnCollector collector = new SessionTurnCollector();
        collector.turn(new A2ATurnState(List.of("Sam"), "ready", false, List.of()));
        collector.prompt("task-1", playOrPass(List.of("Kim")));

        SharedSessionSummary summary = sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.COMPLETED, collector);

        assertThat(summary.prompt().forUserIds()).containsExactly(SAM);
    }

    @Test
    void aTurnThatIsOverCompletesTheSession() {
        SessionTurnCollector collector = new SessionTurnCollector();
        collector.turn(new A2ATurnState(List.of("Alex", "Sam"), "rematch", true, List.of()));

        sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.COMPLETED, collector);

        assertThat(sharedSession.getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    void aCompletedRunWithNoQuestionClearsTheOldOne() {
        SessionTurnCollector asking = new SessionTurnCollector();
        asking.prompt("task-1", playOrPass(List.of("Alex")));
        sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.COMPLETED, asking);

        sharedSessionService.applyOutcome(SESSION_ID, UUID.randomUUID(), TurnOutcome.COMPLETED, new SessionTurnCollector());

        assertThat(sharedSession.getPendingPrompt()).isNull();
    }

    @Test
    void aFailedRunLeavesTheSessionAsItWas() {
        sharedSession.setTurnState(new SessionTurn(List.of(SAM), "answer", false, List.of()));

        SessionTurnCollector collector = new SessionTurnCollector();
        collector.turn(new A2ATurnState(List.of("Alex"), "answer", false, List.of()));

        sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.FAILED, collector);

        assertThat(sharedSession.getTurnState()).isEqualTo(new SessionTurn(List.of(SAM), "answer", false, List.of()));
    }

    @Test
    void aRunOfASessionClosedWhileItRanChangesNothing() {
        sharedSession.setStatus(SessionStatus.CLOSED);

        SessionTurnCollector collector = new SessionTurnCollector();
        collector.turn(new A2ATurnState(List.of("Alex"), "answer", false, List.of()));

        sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.COMPLETED, collector);

        assertThat(sharedSession.getTurnState()).isNull();
        verify(sharedSessionRepository, never()).save(any());
    }

    @Test
    void leavingClosesTheSessionForEveryoneAndTellsTheOthers() {
        sharedSessionService.leave(SESSION_ID, SAM);

        assertThat(sharedSession.getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(participants.get(1).hasLeft()).isTrue();
        verify(redisStreamService).publish(eq(CHAT_ID), eq(ALEX), eq("CUSTOM"), any());
        verify(chatService, never()).deleteSharedChat(any());
    }

    @Test
    void theChatGoesOnceEveryParticipantHasLeft() {
        participants.getFirst().setLeftAt(NOW);

        sharedSessionService.leave(SESSION_ID, SAM);

        verify(chatService).deleteSharedChat(CHAT_ID);
    }

    @Test
    void yourTurnIsAnsweredForTheViewerOfAnActiveSession() {
        sharedSession.setTurnState(new SessionTurn(List.of(SAM), "answer", false, List.of()));

        assertThat(sharedSessionService.summary(sharedSession, participants, SAM, false).yourTurn()).isTrue();
        assertThat(sharedSessionService.summary(sharedSession, participants, ALEX, false).yourTurn()).isFalse();
        assertThat(sharedSessionService.summary(sharedSession, participants, null, false).yourTurn()).isNull();

        sharedSession.setStatus(SessionStatus.COMPLETED);
        assertThat(sharedSessionService.summary(sharedSession, participants, SAM, false).yourTurn()).isFalse();
    }
}
