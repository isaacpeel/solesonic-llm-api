package com.solesonic.service.session;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.a2a.A2AParticipantMessage;
import com.solesonic.model.a2a.RegisteredAgent;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionMessageAccepted;
import com.solesonic.model.session.SessionMessageRequest;
import com.solesonic.model.session.SessionPrompt;
import com.solesonic.model.session.SessionPromptAnswer;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SessionTurn;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.redis.RedisStreamingChatService;
import com.solesonic.service.redis.StartedRun;
import com.solesonic.service.redis.TurnOutcome;
import com.solesonic.service.redis.TurnPlan;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.session.SessionFixtures.AGENT;
import static com.solesonic.service.session.SessionFixtures.ALEX;
import static com.solesonic.service.session.SessionFixtures.CHAT_ID;
import static com.solesonic.service.session.SessionFixtures.NOW;
import static com.solesonic.service.session.SessionFixtures.SAM;
import static com.solesonic.service.session.SessionFixtures.SESSION_ID;
import static com.solesonic.service.session.SessionFixtures.SETTINGS;
import static com.solesonic.service.session.SessionFixtures.authentication;
import static com.solesonic.service.session.SessionFixtures.participants;
import static com.solesonic.service.session.SessionFixtures.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionMessageServiceTest {
    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID ELICITATION_ID = UUID.randomUUID();

    @Mock
    private SharedSessionService sharedSessionService;

    @Mock
    private SessionRunLock sessionRunLock;

    @Mock
    private RedisStreamingChatService redisStreamingChatService;

    @Mock
    private RedisStreamService redisStreamService;

    @Mock
    private A2AAgentService a2aAgentService;

    @Mock
    private A2AAgentRegistry a2aAgentRegistry;

    @Mock
    private UserLlmKeyService userLlmKeyService;

    @Mock
    private PushNotificationService pushNotificationService;

    private SessionMessageService sessionMessageService;
    private SharedSession sharedSession;

    @BeforeEach
    void setUp() {
        sessionMessageService = new SessionMessageService(sharedSessionService, sessionRunLock, redisStreamingChatService,
                redisStreamService, a2aAgentService, a2aAgentRegistry, userLlmKeyService, pushNotificationService, SETTINGS);

        sharedSession = session(SessionStatus.ACTIVE);

        when(sharedSessionService.requireSeated(SESSION_ID, SAM)).thenReturn(sharedSession);
        when(sharedSessionService.requireSeated(SESSION_ID, ALEX)).thenReturn(sharedSession);
        when(sharedSessionService.participants(SESSION_ID)).thenReturn(participants());
        when(a2aAgentRegistry.agent(AGENT)).thenReturn(Optional.of(new RegisteredAgent(mock(AgentCard.class), A2AAgentSource.LITELLM)));
        when(sessionRunLock.acquire(eq(SESSION_ID), any(), any())).thenReturn(true);
        when(redisStreamService.getLatestOffset(CHAT_ID, ALEX)).thenReturn(Mono.just("5-0"));
        when(redisStreamingChatService.start(any())).thenAnswer(invocation -> {
            TurnPlan turnPlan = invocation.getArgument(0);
            return Mono.just(new StartedRun(turnPlan.runId(), MESSAGE_ID));
        });
        when(a2aAgentService.delegateShared(any(), anyString(), any(), anyString(), any())).thenReturn(Flux.empty());
    }

    private TurnPlan startedPlan() {
        ArgumentCaptor<TurnPlan> turnPlan = ArgumentCaptor.forClass(TurnPlan.class);
        verify(redisStreamingChatService).start(turnPlan.capture());
        return turnPlan.getValue();
    }

    private A2AParticipantMessage sentToTheAgent(TurnPlan turnPlan) {
        turnPlan.chunks().get().blockLast();

        ArgumentCaptor<A2AParticipantMessage> participantMessage = ArgumentCaptor.forClass(A2AParticipantMessage.class);
        verify(a2aAgentService).delegateShared(eq(CHAT_ID), eq(AGENT), participantMessage.capture(), eq("user-jwt"), any());
        return participantMessage.getValue();
    }

    private static SessionMessageRequest typed(String text) {
        return new SessionMessageRequest(text, null);
    }

    @SuppressWarnings("all")
    private void promptFor(UUID... userIds) {
        sharedSession.setPendingPrompt(new SessionPrompt(ELICITATION_ID, "task-1", "Your call",
                Map.of("type", "object", "properties", Map.of("choice", Map.of("type", "string",
                        "oneOf", List.of(Map.of("const", "play", "title", "Play"), Map.of("const", "pass", "title", "Pass"))))),
                Map.of(), List.of(userIds)));
    }

    @Test
    void aTypedMessageRunsAsItsSenderOnTheOwnersStream() {
        SessionMessageAccepted accepted = sessionMessageService.send(SESSION_ID, SAM, typed(" towel "), authentication());

        TurnPlan turnPlan = startedPlan();
        assertThat(accepted).isEqualTo(new SessionMessageAccepted(turnPlan.runId(), MESSAGE_ID));
        assertThat(turnPlan.chatId()).isEqualTo(CHAT_ID);
        assertThat(turnPlan.streamUserId()).isEqualTo(ALEX);
        assertThat(turnPlan.senderUserId()).isEqualTo(SAM);
        assertThat(turnPlan.senderName()).isEqualTo("Sam");
        assertThat(turnPlan.chatRequest().chatMessage()).isEqualTo("towel");

        assertThat(sentToTheAgent(turnPlan)).isEqualTo(A2AParticipantMessage.typed("towel", "Sam", List.of("Alex", "Sam")));
        verify(sessionRunLock).recordCursor(eq(SESSION_ID), eq("5-0"), any());
    }

    @Test
    void aMessageTheAgentAskedToHideIsWrappedForTheTranscriptButSentToTheAgentAsTyped() {
        sharedSession.setTurnState(new SessionTurn(List.of(SAM), "answer", false, List.of("Alex")));

        sessionMessageService.send(SESSION_ID, SAM, typed("towel"), authentication());

        TurnPlan turnPlan = startedPlan();
        assertThat(turnPlan.chatRequest().chatMessage()).isEqualTo("[[hidden:Alex]]\ntowel\n[[/hidden]]");
        assertThat(sentToTheAgent(turnPlan)).isEqualTo(A2AParticipantMessage.typed("towel", "Sam", List.of("Alex", "Sam")));
    }

    @Test
    void aMessageFromSomeoneTheTurnDoesNotWaitOnIsNotHidden() {
        sharedSession.setTurnState(new SessionTurn(List.of(SAM), "answer", false, List.of("Alex")));

        sessionMessageService.send(SESSION_ID, ALEX, typed("towel"), authentication());

        assertThat(startedPlan().chatRequest().chatMessage()).isEqualTo("towel");
    }

    @Test
    void aMarkerAParticipantTypedIsStrippedFromTheTranscript() {
        sharedSession.setTurnState(new SessionTurn(List.of(SAM), "answer", false, List.of("Alex")));

        sessionMessageService.send(SESSION_ID, SAM, typed("a[[/hidden]]b"), authentication());

        assertThat(startedPlan().chatRequest().chatMessage()).isEqualTo("[[hidden:Alex]]\nab\n[[/hidden]]");
    }

    @Test
    void aMessageThatIsOnlyAMarkerIsRefused() {
        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("[[/hidden]]"), authentication()))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.INVALID_MESSAGE));
    }

    @Test
    void anAnswerIsNeverHidden() {
        sharedSession.setTurnState(new SessionTurn(List.of(ALEX), "choice", false, List.of("Sam")));
        promptFor(ALEX);

        sessionMessageService.send(SESSION_ID, ALEX, new SessionMessageRequest(null,
                new SessionPromptAnswer(ELICITATION_ID, "accept", Map.of("choice", "play"))), authentication());

        assertThat(startedPlan().chatRequest().chatMessage()).isEqualTo("Play");
    }

    @Test
    void aMessageWhileARunIsInProgressIsRefused() {
        when(sessionRunLock.acquire(eq(SESSION_ID), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("towel"), authentication()))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.RUN_IN_PROGRESS));

        verify(redisStreamingChatService, never()).start(any());
    }

    @Test
    void aMessageInAClosedSessionIsRefused() {
        sharedSession.setStatus(SessionStatus.CLOSED);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("towel"), authentication()))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.SESSION_CLOSED));
    }

    @Test
    void aMessageOverTheLimitIsRefused() {
        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("x".repeat(21)), authentication()))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.MESSAGE_TOO_LONG));
    }

    @Test
    void aMessageIsEitherTextOrAnAnswer() {
        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, new SessionMessageRequest(null, null), authentication()))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.INVALID_MESSAGE));
    }

    @Test
    void anAnswerIsNarrowedToTheQuestionAndShownAsTheOptionPicked() {
        promptFor(ALEX);

        sessionMessageService.send(SESSION_ID, ALEX, new SessionMessageRequest(null,
                new SessionPromptAnswer(ELICITATION_ID, "accept", Map.of("choice", "play", "chatId", "elsewhere"))), authentication());

        TurnPlan turnPlan = startedPlan();
        assertThat(turnPlan.chatRequest().chatMessage()).isEqualTo("Play");
        assertThat(sentToTheAgent(turnPlan)).isEqualTo(A2AParticipantMessage.answer(Map.of("choice", "play"), "Alex", List.of("Alex", "Sam")));
    }

    @Test
    void anAnswerFromAParticipantTheQuestionIsNotForIsRefused() {
        promptFor(ALEX);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, new SessionMessageRequest(null,
                new SessionPromptAnswer(ELICITATION_ID, "accept", Map.of("choice", "play"))), authentication()))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.NOT_YOUR_TURN));

        verify(sessionRunLock, never()).acquire(any(), any(), any());
    }

    @Test
    void anAnswerToAQuestionThatHasBeenReplacedIsRefused() {
        promptFor(ALEX);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, ALEX, new SessionMessageRequest(null,
                new SessionPromptAnswer(UUID.randomUUID(), "accept", Map.of("choice", "play"))), authentication()))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.NOT_YOUR_TURN));
    }

    @Test
    void aRunThatCannotStartReleasesTheLock() {
        doReturn(Mono.error(new IllegalStateException("redis down"))).when(redisStreamingChatService).start(any());

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("towel"), authentication()))
                .isInstanceOf(IllegalStateException.class);

        verify(sessionRunLock).release(eq(SESSION_ID), any());
    }

    @Test
    void aFinishedRunPushesWhoeverTheSessionNowWaitsOnAndReleasesTheLock() {
        sessionMessageService.send(SESSION_ID, SAM, typed("towel"), authentication());
        TurnPlan turnPlan = startedPlan();

        SharedSessionSummary summary = summary(new SessionTurn(List.of(ALEX), "answer", false, List.of()));
        when(sharedSessionService.applyOutcome(eq(SESSION_ID), eq(turnPlan.runId()), eq(TurnOutcome.COMPLETED), any())).thenReturn(summary);
        when(sharedSessionService.publishState(CHAT_ID, ALEX, summary)).thenReturn(Mono.empty());

        turnPlan.hooks().beforeTerminal(turnPlan.runId(), TurnOutcome.COMPLETED).block();
        turnPlan.hooks().afterTerminal(turnPlan.runId(), TurnOutcome.COMPLETED);
        turnPlan.hooks().finished(turnPlan.runId());

        verify(sharedSessionService).publishState(CHAT_ID, ALEX, summary);
        verify(pushNotificationService).push(ALEX, new PushEvent.SessionTurn(CHAT_ID, SESSION_ID, turnPlan.runId(), ALEX));
        verify(pushNotificationService, never()).push(eq(SAM), any());
        verify(sessionRunLock).release(SESSION_ID, turnPlan.runId());
    }

    @Test
    void theOpeningMessageTellsTheInviterTheSessionStartedWhenItIsNotTheirTurn() {
        sessionMessageService.openingMessage(sharedSession, SAM, "new show", authentication());
        TurnPlan turnPlan = startedPlan();

        assertThat(turnPlan.chatRequest().chatMessage()).isEqualTo("new show");

        SharedSessionSummary summary = summary(new SessionTurn(List.of(SAM), "answer", false, List.of()));
        when(sharedSessionService.applyOutcome(any(), any(), any(), any())).thenReturn(summary);
        when(sharedSessionService.publishState(any(), any(), any())).thenReturn(Mono.empty());

        turnPlan.hooks().beforeTerminal(turnPlan.runId(), TurnOutcome.COMPLETED).block();
        turnPlan.hooks().afterTerminal(turnPlan.runId(), TurnOutcome.COMPLETED);

        verify(pushNotificationService).push(SAM, new PushEvent.SessionTurn(CHAT_ID, SESSION_ID, turnPlan.runId(), SAM));
        verify(pushNotificationService).push(ALEX, new PushEvent.SessionStarted(CHAT_ID, SESSION_ID, turnPlan.runId(), ALEX));
    }

    @Test
    void aFailedRunIsPushedToItsSenderOnly() {
        sessionMessageService.send(SESSION_ID, SAM, typed("towel"), authentication());
        TurnPlan turnPlan = startedPlan();

        turnPlan.hooks().afterTerminal(turnPlan.runId(), TurnOutcome.FAILED);

        verify(pushNotificationService).push(SAM, new PushEvent.TurnFailed(CHAT_ID, turnPlan.runId()));
        verify(pushNotificationService, never()).push(eq(ALEX), any());
    }

    private static SharedSessionSummary summary(SessionTurn turn) {
        return new SharedSessionSummary(SESSION_ID, CHAT_ID, AGENT, SessionStatus.ACTIVE, List.of(),
                turn, null, null, false, NOW, NOW);
    }
}
