package com.solesonic.service.session;

import com.solesonic.model.a2a.A2APrompt;
import com.solesonic.model.a2a.A2ATurnState;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionInvite;
import com.solesonic.model.session.SessionInviteStatus;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SessionTurn;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.model.user.UserProfile;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.session.SessionInviteRepository;
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
import static com.solesonic.service.session.SessionFixtures.CONVERSATION_SETTINGS;
import static com.solesonic.service.session.SessionFixtures.KIM;
import static com.solesonic.service.session.SessionFixtures.conversationSession;
import static com.solesonic.service.session.SessionFixtures.participant;
import static com.solesonic.service.session.SessionFixtures.participants;
import static com.solesonic.service.session.SessionFixtures.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
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
    private SessionInviteRepository sessionInviteRepository;

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
        sharedSessionService = new SharedSessionService(sharedSessionRepository, sessionParticipantRepository, sessionInviteRepository,
                chatRepository, chatService, chatMessageService, userProfileService, a2aStickyAgentService, redisStreamService,
                sessionRunLock, CONVERSATION_SETTINGS, Clock.fixed(NOW.toInstant(), NOW.getZone()));

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

    // ---- conversation mode

    private SessionException refused(Runnable action) {
        try {
            action.run();
        } catch (SessionException sessionException) {
            return sessionException;
        }
        throw new AssertionError("not refused");
    }

    private void aConversation(SessionParticipant... seated) {
        sharedSession = conversationSession(SessionStatus.ACTIVE);
        participants = new java.util.ArrayList<>(List.of(seated));
        when(sharedSessionRepository.lockById(SESSION_ID)).thenReturn(Optional.of(sharedSession));
        when(sessionParticipantRepository.findBySessionIdOrderBySeatAsc(SESSION_ID)).thenReturn(participants);
    }

    private SessionParticipant savedParticipant() {
        ArgumentCaptor<SessionParticipant> saved = ArgumentCaptor.forClass(SessionParticipant.class);
        verify(sessionParticipantRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void aNonOwnerLeavingAConversationOnlyLeavesAndTellsTheOthers() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"), participant(KIM, 2, "Kim"));

        sharedSessionService.leave(SESSION_ID, SAM);

        assertThat(participants.get(1).hasLeft()).isTrue();
        assertThat(participants.get(0).hasLeft()).isFalse();
        assertThat(sharedSession.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        verify(sessionInviteRepository, never()).cancelPendingForSession(any(), any());
        verify(a2aStickyAgentService, never()).deactivateTask(any());
        verify(redisStreamService).publish(eq(CHAT_ID), eq(ALEX), eq("CUSTOM"), any());
        verify(chatService, never()).deleteSharedChat(any());
    }

    @Test
    void theOwnerLeavingAConversationClosesItAndCancelsItsPendingInvites() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"));

        sharedSessionService.leave(SESSION_ID, ALEX);

        assertThat(participants.getFirst().hasLeft()).isTrue();
        assertThat(sharedSession.getStatus()).isEqualTo(SessionStatus.CLOSED);
        verify(sessionInviteRepository).cancelPendingForSession(SESSION_ID, NOW);
        verify(a2aStickyAgentService, never()).deactivateTask(any());
        verify(redisStreamService).publish(eq(CHAT_ID), eq(ALEX), eq("CUSTOM"), any());
        verify(chatService, never()).deleteSharedChat(any());
    }

    @Test
    void aConversationsChatGoesWhenTheLastParticipantLeavesAfterTheOwner() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"));
        participants.getFirst().setLeftAt(NOW);
        sharedSession.setStatus(SessionStatus.CLOSED);

        sharedSessionService.leave(SESSION_ID, SAM);

        verify(chatService).deleteSharedChat(CHAT_ID);
    }

    @Test
    void someoneWhoAlreadyLeftCannotLeaveAConversationAgain() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"));
        participants.get(1).setLeftAt(NOW);

        assertThat(refused(() -> sharedSessionService.leave(SESSION_ID, SAM)).getErrorCode()).isEqualTo(SessionErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    void anAgentSessionStillEndsItsStickyTaskWhenLeft() {
        sharedSessionService.leave(SESSION_ID, SAM);

        verify(a2aStickyAgentService).deactivateTask(CHAT_ID);
        verify(sessionInviteRepository, never()).cancelPendingForSession(any(), any());
    }

    @Test
    void aConversationRunOnlyRecordsItselfAndIgnoresAgentTurnState() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"));
        SessionTurnCollector collector = new SessionTurnCollector();
        collector.turn(new A2ATurnState(List.of("Sam"), "answer", false, List.of()));
        collector.prompt("task-1", playOrPass(List.of("Sam")));

        SharedSessionSummary summary = sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.COMPLETED, collector);

        assertThat(sharedSession.getLastRunId()).isEqualTo(RUN_ID);
        assertThat(sharedSession.getUpdated()).isEqualTo(NOW);
        assertThat(sharedSession.getTurnState()).isNull();
        assertThat(sharedSession.getPendingPrompt()).isNull();
        assertThat(summary.turn()).isNull();
        assertThat(summary.prompt()).isNull();
        assertThat(sharedSession.getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void aFailedConversationRunStillRecordsItsId() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"));

        sharedSessionService.applyOutcome(SESSION_ID, RUN_ID, TurnOutcome.FAILED, new SessionTurnCollector());

        assertThat(sharedSession.getLastRunId()).isEqualTo(RUN_ID);
    }

    // ---- join

    @Test
    void aNewcomerTakesTheNextSeatAndAUniqueNameAndTheOthersAreTold() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Kim"));
        when(userProfileService.profile(KIM)).thenReturn(Optional.of(new UserProfile(KIM, "lee", "k@example.com", "Kim")));

        SharedSession joined = sharedSessionService.join(SESSION_ID, KIM);

        assertThat(joined).isSameAs(sharedSession);
        SessionParticipant newcomer = savedParticipant();
        assertThat(newcomer.getUserId()).isEqualTo(KIM);
        assertThat(newcomer.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(newcomer.getSeat()).isEqualTo(2);
        assertThat(newcomer.getName()).isEqualTo("Kim L.");
        assertThat(newcomer.getJoined()).isEqualTo(NOW);
        assertThat(newcomer.hasLeft()).isFalse();
        assertThat(sharedSession.getUpdated()).isEqualTo(NOW);
        verify(redisStreamService).publish(eq(CHAT_ID), eq(ALEX), eq("CUSTOM"), any());
    }

    @Test
    void theNextSeatFollowsTheHighestSeatEvenWhenSomeoneLeft() {
        SessionParticipant left = participant(SAM, 1, "Sam");
        left.setLeftAt(NOW);
        aConversation(participant(ALEX, 0, "Alex"), left, participant(UUID.randomUUID(), 2, "Dee"));

        sharedSessionService.join(SESSION_ID, KIM);

        assertThat(savedParticipant().getSeat()).isEqualTo(3);
    }

    @Test
    void aNameHeldByAnyRowEvenOneWhoLeftIsNotReused() {
        SessionParticipant left = participant(SAM, 1, "Kim");
        left.setLeftAt(NOW);
        aConversation(participant(ALEX, 0, "Alex"), left);
        when(userProfileService.profile(KIM)).thenReturn(Optional.of(new UserProfile(KIM, "kim", null, "kim")));

        sharedSessionService.join(SESSION_ID, KIM);

        assertThat(savedParticipant().getName()).isEqualTo("kim K.");
    }

    @Test
    void aSessionThatIsOverCannotBeJoined() {
        aConversation(participant(ALEX, 0, "Alex"));
        sharedSession.setStatus(SessionStatus.CLOSED);

        assertThat(refused(() -> sharedSessionService.join(SESSION_ID, KIM)).getErrorCode()).isEqualTo(SessionErrorCode.SESSION_CLOSED);
        verify(sessionParticipantRepository, never()).save(any());
    }

    @Test
    void aFullSessionCannotBeJoined() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"),
                participant(UUID.randomUUID(), 2, "Dee"), participant(UUID.randomUUID(), 3, "Lou"));

        assertThat(refused(() -> sharedSessionService.join(SESSION_ID, KIM)).getErrorCode()).isEqualTo(SessionErrorCode.TOO_MANY_PARTICIPANTS);
        verify(sessionParticipantRepository, never()).save(any());
    }

    @Test
    void thoseWhoLeftDoNotCountTowardsTheMaximum() {
        SessionParticipant left = participant(SAM, 1, "Sam");
        left.setLeftAt(NOW);
        aConversation(participant(ALEX, 0, "Alex"), left,
                participant(UUID.randomUUID(), 2, "Dee"), participant(UUID.randomUUID(), 3, "Lou"));

        sharedSessionService.join(SESSION_ID, KIM);

        assertThat(savedParticipant().getUserId()).isEqualTo(KIM);
    }

    @Test
    void someoneWhoLeftRejoinsOnTheirOwnRowInsteadOfInsertingASecond() {
        SessionParticipant left = participant(SAM, 1, "Sam");
        left.setLeftAt(NOW.minusHours(1));
        aConversation(participant(ALEX, 0, "Alex"), left);

        sharedSessionService.join(SESSION_ID, SAM);

        assertThat(left.hasLeft()).isFalse();
        assertThat(left.getSeat()).isEqualTo(1);
        assertThat(left.getName()).isEqualTo("Sam");
        assertThat(savedParticipant()).isSameAs(left);
        verify(sessionParticipantRepository, times(1)).save(any());
    }

    @Test
    void joiningWhenAlreadySeatedChangesNothing() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"));

        sharedSessionService.join(SESSION_ID, SAM);

        verify(sessionParticipantRepository, never()).save(any());
    }

    @Test
    void aSessionThatDoesNotExistCannotBeJoined() {
        when(sharedSessionRepository.lockById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sharedSessionService.join(SESSION_ID, KIM)).isInstanceOf(SessionException.class);
    }

    // ---- pending invitees

    private static SessionInvite waiting(UUID sessionId, UUID inviteeUserId) {
        SessionInvite sessionInvite = new SessionInvite();
        sessionInvite.setId(UUID.randomUUID());
        sessionInvite.setSessionId(sessionId);
        sessionInvite.setInviteeUserId(inviteeUserId);
        sessionInvite.setStatus(SessionInviteStatus.PENDING);
        return sessionInvite;
    }

    @Test
    void aSummaryListsWhoHasNotAnsweredYet() {
        aConversation(participant(ALEX, 0, "Alex"));
        when(sessionInviteRepository.findBySessionIdInAndStatus(List.of(SESSION_ID), SessionInviteStatus.PENDING))
                .thenReturn(List.of(waiting(SESSION_ID, KIM)));
        when(userProfileService.profiles(any())).thenReturn(Map.of(KIM, new UserProfile(KIM, "kim", "k@example.com", "Kim L")));

        SharedSessionSummary summary = sharedSessionService.summary(sharedSession, participants, ALEX, false);

        assertThat(summary.pendingInvitees()).containsExactly(new SharedSessionSummary.PendingInvitee(KIM, "kim", "Kim L"));
    }

    @Test
    void anAgentSessionHasNoPendingInviteesAndAsksNothing() {
        SharedSessionSummary summary = sharedSessionService.summary(sharedSession, participants, ALEX, false);

        assertThat(summary.pendingInvitees()).isEmpty();
        verify(sessionInviteRepository, never()).findBySessionIdInAndStatus(any(), any());
    }

    @Test
    void listingFindsEverySessionsPendingInviteesInOneQuery() {
        SharedSession first = conversationSession(SessionStatus.ACTIVE);
        SharedSession second = conversationSession(SessionStatus.ACTIVE);
        second.setId(UUID.randomUUID());
        second.setChatId(UUID.randomUUID());
        UUID dee = UUID.randomUUID();
        when(sharedSessionRepository.findAllForParticipant(eq(ALEX), any(), any()))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 10), 2));
        when(sessionParticipantRepository.findBySessionIdInOrderBySeatAsc(any())).thenReturn(List.of(
                participant(ALEX, 0, "Alex")));
        when(sessionInviteRepository.findBySessionIdInAndStatus(any(), eq(SessionInviteStatus.PENDING)))
                .thenReturn(List.of(waiting(first.getId(), KIM), waiting(second.getId(), dee)));
        when(userProfileService.profiles(any())).thenReturn(Map.of(
                KIM, new UserProfile(KIM, "kim", null, "Kim"), dee, new UserProfile(dee, "dee", null, "Dee")));

        var page = sharedSessionService.list(ALEX, java.util.Set.of(SessionStatus.ACTIVE), PageRequest.of(0, 10));

        verify(sessionInviteRepository, times(1)).findBySessionIdInAndStatus(any(), any());
        assertThat(page.getContent().get(0).pendingInvitees()).extracting(SharedSessionSummary.PendingInvitee::username).containsExactly("kim");
        assertThat(page.getContent().get(1).pendingInvitees()).extracting(SharedSessionSummary.PendingInvitee::username).containsExactly("dee");
    }

    @Test
    void sessionsThatAreOverTakeTheirPendingInvitesWithThem() {
        when(sharedSessionRepository.expireIdle(eq(SessionMode.AGENT), any(), any())).thenReturn(2);
        when(sharedSessionRepository.expireIdle(eq(SessionMode.CONVERSATION), any(), any())).thenReturn(1);

        assertThat(sharedSessionService.expireIdle()).isEqualTo(3);

        verify(sessionInviteRepository).cancelPendingForOverSessions(NOW);
    }

    @Test
    void anAgentSessionExpiresAfterTheAgentIdleWindowAndAConversationAfterItsOwn() {
        sharedSessionService.expireIdle();

        verify(sharedSessionRepository).expireIdle(SessionMode.AGENT, NOW.minusDays(7), NOW);
        verify(sharedSessionRepository).expireIdle(SessionMode.CONVERSATION, NOW.minusDays(90), NOW);
    }

    @Test
    void theFinalLeaveIsRefusedWhileARunIsInProgressAndDeletesNothing() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"));
        participants.getFirst().setLeftAt(NOW);
        sharedSession.setStatus(SessionStatus.CLOSED);
        when(sessionRunLock.isHeld(SESSION_ID)).thenReturn(true);

        assertThat(refused(() -> sharedSessionService.leave(SESSION_ID, SAM)).getErrorCode()).isEqualTo(SessionErrorCode.RUN_IN_PROGRESS);

        verify(chatService, never()).deleteSharedChat(any());
        verify(sessionParticipantRepository, never()).save(any());
    }

    @Test
    void aLeaveThatLeavesSomeoneSeatedIsFineWhileARunIsInProgress() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"), participant(KIM, 2, "Kim"));
        when(sessionRunLock.isHeld(SESSION_ID)).thenReturn(true);

        sharedSessionService.leave(SESSION_ID, SAM);

        assertThat(participants.get(1).hasLeft()).isTrue();
    }

    @Test
    void theLeaveIsOnlyAnnouncedOnceItsTransactionHasCommitted() {
        aConversation(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"), participant(KIM, 2, "Kim"));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();

        try {
            sharedSessionService.leave(SESSION_ID, SAM);

            verify(redisStreamService, never()).publish(any(), any(), anyString(), any());

            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        verify(redisStreamService).publish(eq(CHAT_ID), eq(ALEX), eq("CUSTOM"), any());
    }

    @Test
    void inAConversationOnlyTheRunsSenderOrTheOwnerMayCancelIt() {
        sharedSession = conversationSession(SessionStatus.ACTIVE);
        participants = new java.util.ArrayList<>(List.of(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"), participant(KIM, 2, "Kim")));
        when(sharedSessionRepository.findForSeatedParticipant(SESSION_ID, SAM)).thenReturn(Optional.of(sharedSession));
        when(sharedSessionRepository.findForSeatedParticipant(SESSION_ID, KIM)).thenReturn(Optional.of(sharedSession));
        when(sharedSessionRepository.findForSeatedParticipant(SESSION_ID, ALEX)).thenReturn(Optional.of(sharedSession));
        when(sessionParticipantRepository.findBySessionIdOrderBySeatAsc(SESSION_ID)).thenReturn(participants);
        when(sessionRunLock.isHeld(SESSION_ID)).thenReturn(true);
        when(chatMessageService.lastUserSender(CHAT_ID)).thenReturn(Optional.of(SAM));

        assertThat(sharedSessionService.requireMayCancel(SESSION_ID, SAM)).isSameAs(sharedSession);
        assertThat(sharedSessionService.requireMayCancel(SESSION_ID, ALEX)).isSameAs(sharedSession);
        assertThat(refused(() -> sharedSessionService.requireMayCancel(SESSION_ID, KIM)).getErrorCode())
                .isEqualTo(SessionErrorCode.NOT_SESSION_OWNER);
    }

    @Test
    void whenNoRunIsInProgressACancelIsHarmlessForAnySeatedParticipant() {
        sharedSession = conversationSession(SessionStatus.ACTIVE);
        when(sharedSessionRepository.findForSeatedParticipant(SESSION_ID, KIM)).thenReturn(Optional.of(sharedSession));
        when(sessionRunLock.isHeld(SESSION_ID)).thenReturn(false);

        assertThat(sharedSessionService.requireMayCancel(SESSION_ID, KIM)).isSameAs(sharedSession);
    }

    @Test
    void anAgentSessionStillLetsAnySeatedParticipantCancel() {
        when(sharedSessionRepository.findForSeatedParticipant(SESSION_ID, SAM)).thenReturn(Optional.of(sharedSession));
        when(sessionRunLock.isHeld(SESSION_ID)).thenReturn(true);

        assertThat(sharedSessionService.requireMayCancel(SESSION_ID, SAM)).isSameAs(sharedSession);
    }

    @Test
    void sessionNamesComeFromTheirChatsInOneLookup() {
        when(sharedSessionRepository.findAllById(any())).thenReturn(List.of(sharedSession));
        com.solesonic.model.chat.history.Chat chat = new com.solesonic.model.chat.history.Chat();
        chat.setId(CHAT_ID);
        chat.setName("Trip plans");
        when(chatRepository.findAllById(any())).thenReturn(List.of(chat));

        assertThat(sharedSessionService.sessionNames(java.util.Set.of(SESSION_ID))).containsExactly(Map.entry(SESSION_ID, "Trip plans"));
    }

    @Test
    void noSessionIdsMeansNoLookup() {
        assertThat(sharedSessionService.sessionNames(java.util.Set.of())).isEmpty();
        verify(sharedSessionRepository, never()).findAllById(any());
    }
}
