package com.solesonic.service.session;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.a2a.RegisteredAgent;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionInvite;
import com.solesonic.model.session.SessionInviteRequest;
import com.solesonic.model.session.SessionInviteStatus;
import com.solesonic.model.session.SessionMessageAccepted;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SharedSession;
import com.solesonic.repository.session.SessionInviteRepository;
import com.solesonic.repository.user.UserProfileRepository;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.user.UserProfileService;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.session.SessionFixtures.AGENT;
import static com.solesonic.service.session.SessionFixtures.ALEX;
import static com.solesonic.service.session.SessionFixtures.NOW;
import static com.solesonic.service.session.SessionFixtures.SAM;
import static com.solesonic.service.session.SessionFixtures.SETTINGS;
import static com.solesonic.service.session.SessionFixtures.authentication;
import static com.solesonic.service.session.SessionFixtures.participants;
import static com.solesonic.service.session.SessionFixtures.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionInviteServiceTest {
    private static final UUID INVITE_ID = UUID.randomUUID();
    private static final String OPENING_MESSAGE = "new show";

    @Mock
    private SessionInviteRepository sessionInviteRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private UserLlmKeyService userLlmKeyService;

    @Mock
    private A2AAgentRegistry a2aAgentRegistry;

    @Mock
    private SharedSessionService sharedSessionService;

    @Mock
    private SessionMessageService sessionMessageService;

    @Mock
    private PushNotificationService pushNotificationService;

    private SessionInviteService sessionInviteService;

    @BeforeEach
    void setUp() {
        sessionInviteService = new SessionInviteService(sessionInviteRepository, userProfileRepository, userProfileService,
                userLlmKeyService, a2aAgentRegistry, sharedSessionService, sessionMessageService, pushNotificationService, SETTINGS,
                Clock.fixed(NOW.toInstant(), NOW.getZone()));

        when(userProfileRepository.findActiveUserIdByUsername("sam")).thenReturn(Optional.of(SAM));
        when(userProfileRepository.findActiveUserIdByUsername("alex")).thenReturn(Optional.of(ALEX));
        when(userProfileService.profiles(any())).thenReturn(Map.of());
        when(a2aAgentRegistry.agent(AGENT)).thenReturn(Optional.of(new RegisteredAgent(mock(AgentCard.class), A2AAgentSource.LITELLM)));
        when(sessionInviteRepository.save(any(SessionInvite.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void assertRefused(Runnable action, SessionErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SessionException.class, sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(errorCode));
    }

    private static SessionInviteRequest request(String username) {
        return new SessionInviteRequest(username, AGENT, OPENING_MESSAGE);
    }

    private static SessionInvite pendingInvite(String openingMessage) {
        SessionInvite sessionInvite = new SessionInvite();
        sessionInvite.setId(INVITE_ID);
        sessionInvite.setInviterUserId(ALEX);
        sessionInvite.setInviteeUserId(SAM);
        sessionInvite.setAgentName(AGENT);
        sessionInvite.setOpeningMessage(openingMessage);
        sessionInvite.setStatus(SessionInviteStatus.PENDING);
        sessionInvite.setCreated(NOW);
        sessionInvite.setExpires(NOW.plusHours(1));
        return sessionInvite;
    }

    @Test
    void anInviteIsSentToTheNamedUserAndPushedToThem() {
        sessionInviteService.invite(ALEX, new SessionInviteRequest(" sam ", AGENT, "  new show  "));

        ArgumentCaptor<SessionInvite> saved = ArgumentCaptor.forClass(SessionInvite.class);
        verify(sessionInviteRepository).save(saved.capture());
        assertThat(saved.getValue().getInviteeUserId()).isEqualTo(SAM);
        assertThat(saved.getValue().getAgentName()).isEqualTo(AGENT);
        assertThat(saved.getValue().getOpeningMessage()).isEqualTo(OPENING_MESSAGE);
        assertThat(saved.getValue().getStatus()).isEqualTo(SessionInviteStatus.PENDING);
        assertThat(saved.getValue().getExpires()).isEqualTo(NOW.plusHours(24));
        verify(pushNotificationService).push(SAM, new PushEvent.SessionInvited(saved.getValue().getId()));
    }

    @Test
    void anInviteNeedNotOpenWithAMessage() {
        sessionInviteService.invite(ALEX, new SessionInviteRequest("sam", AGENT, " "));

        ArgumentCaptor<SessionInvite> saved = ArgumentCaptor.forClass(SessionInvite.class);
        verify(sessionInviteRepository).save(saved.capture());
        assertThat(saved.getValue().getOpeningMessage()).isNull();
    }

    @Test
    void anUnknownUserAndYourselfAreTheSameNotFound() {
        assertRefused(() -> sessionInviteService.invite(ALEX, request("nobody")), SessionErrorCode.PARTICIPANT_NOT_FOUND);
        assertRefused(() -> sessionInviteService.invite(ALEX, request("alex")), SessionErrorCode.PARTICIPANT_NOT_FOUND);
    }

    @Test
    void anInviteNeedsAUsernameAndAnAgentThatMayBeShared() {
        assertRefused(() -> sessionInviteService.invite(ALEX, request(" ")), SessionErrorCode.INVALID_INVITE);
        assertRefused(() -> sessionInviteService.invite(ALEX, new SessionInviteRequest("sam", null, null)), SessionErrorCode.INVALID_INVITE);
        assertRefused(() -> sessionInviteService.invite(ALEX, new SessionInviteRequest("sam", "network", null)), SessionErrorCode.INVALID_INVITE);
    }

    @Test
    void anOpeningMessageOverTheLimitIsRefused() {
        assertRefused(() -> sessionInviteService.invite(ALEX, new SessionInviteRequest("sam", AGENT, "x".repeat(21))),
                SessionErrorCode.MESSAGE_TOO_LONG);
    }

    @Test
    void oneOpenInviteBetweenTwoUsers() {
        when(sessionInviteRepository.existsPendingBetween(ALEX, SAM)).thenReturn(true);

        assertRefused(() -> sessionInviteService.invite(ALEX, request("sam")), SessionErrorCode.INVITE_PENDING);
    }

    @Test
    void theInvitesWaitingOnAnswersAreCapped() {
        when(sessionInviteRepository.countByInviterUserIdAndStatus(ALEX, SessionInviteStatus.PENDING)).thenReturn(5L);

        assertRefused(() -> sessionInviteService.invite(ALEX, request("sam")), SessionErrorCode.TOO_MANY_INVITES);
    }

    @Test
    void anInviterWithNoModelKeyCannotInvite() {
        when(userLlmKeyService.requireKey(ALEX)).thenThrow(new LlmKeyMissingException());

        assertThatThrownBy(() -> sessionInviteService.invite(ALEX, request("sam")))
                .isInstanceOf(LlmKeyMissingException.class);
        verify(sessionInviteRepository, never()).save(any());
    }

    @Test
    void sessionsAreUnavailableWithoutTheirAgent() {
        when(a2aAgentRegistry.agent(AGENT)).thenReturn(Optional.empty());

        assertRefused(() -> sessionInviteService.invite(ALEX, request("sam")), SessionErrorCode.AGENT_UNAVAILABLE);
    }

    @Test
    void acceptingStartsTheSessionAndSendsTheOpeningMessageAsTheInvitee() {
        SessionInvite sessionInvite = pendingInvite(OPENING_MESSAGE);
        SharedSession sharedSession = session(SessionStatus.ACTIVE);

        when(sessionInviteRepository.lockForInvitee(INVITE_ID, SAM)).thenReturn(Optional.of(sessionInvite));
        when(sharedSessionService.create(sessionInvite)).thenReturn(sharedSession);
        when(sharedSessionService.participants(sharedSession.getId())).thenReturn(participants());
        when(sessionMessageService.openingMessage(eq(sharedSession), eq(SAM), eq(OPENING_MESSAGE), any()))
                .thenReturn(new SessionMessageAccepted(UUID.randomUUID(), UUID.randomUUID()));

        sessionInviteService.accept(INVITE_ID, SAM, authentication());

        assertThat(sessionInvite.getStatus()).isEqualTo(SessionInviteStatus.ACCEPTED);
        assertThat(sessionInvite.getSessionId()).isEqualTo(sharedSession.getId());
        verify(sessionMessageService).openingMessage(eq(sharedSession), eq(SAM), eq(OPENING_MESSAGE), any());
    }

    @Test
    void anInviteWithNoOpeningMessageStartsQuietly() {
        SessionInvite sessionInvite = pendingInvite(null);
        SharedSession sharedSession = session(SessionStatus.ACTIVE);

        when(sessionInviteRepository.lockForInvitee(INVITE_ID, SAM)).thenReturn(Optional.of(sessionInvite));
        when(sharedSessionService.create(sessionInvite)).thenReturn(sharedSession);
        when(sharedSessionService.participants(sharedSession.getId())).thenReturn(participants());

        sessionInviteService.accept(INVITE_ID, SAM, authentication());

        assertThat(sessionInvite.getStatus()).isEqualTo(SessionInviteStatus.ACCEPTED);
        verify(sessionMessageService, never()).openingMessage(any(), any(), any(), any());
    }

    @Test
    void anOpeningMessageThatFailsDoesNotUndoTheAccept() {
        SessionInvite sessionInvite = pendingInvite(OPENING_MESSAGE);
        SharedSession sharedSession = session(SessionStatus.ACTIVE);

        when(sessionInviteRepository.lockForInvitee(INVITE_ID, SAM)).thenReturn(Optional.of(sessionInvite));
        when(sharedSessionService.create(sessionInvite)).thenReturn(sharedSession);
        when(sharedSessionService.participants(sharedSession.getId())).thenReturn(participants());
        when(sessionMessageService.openingMessage(any(), any(), any(), any())).thenThrow(new IllegalStateException("redis down"));

        sessionInviteService.accept(INVITE_ID, SAM, authentication());

        assertThat(sessionInvite.getStatus()).isEqualTo(SessionInviteStatus.ACCEPTED);
    }

    @Test
    void anInviteThatIsNoLongerOpenCannotBeAccepted() {
        SessionInvite declined = pendingInvite(OPENING_MESSAGE);
        declined.setStatus(SessionInviteStatus.DECLINED);
        when(sessionInviteRepository.lockForInvitee(INVITE_ID, SAM)).thenReturn(Optional.of(declined));

        assertRefused(() -> sessionInviteService.accept(INVITE_ID, SAM, authentication()), SessionErrorCode.INVITE_NOT_PENDING);
        verify(sharedSessionService, never()).create(any());
    }

    @Test
    void anExpiredInviteCannotBeAccepted() {
        SessionInvite expired = pendingInvite(OPENING_MESSAGE);
        expired.setExpires(NOW.minusMinutes(1));
        when(sessionInviteRepository.lockForInvitee(INVITE_ID, SAM)).thenReturn(Optional.of(expired));

        assertRefused(() -> sessionInviteService.accept(INVITE_ID, SAM, authentication()), SessionErrorCode.INVITE_NOT_PENDING);
        verify(sharedSessionService, never()).create(any());
    }

    @Test
    void someoneElsesInviteIsNotFound() {
        when(sessionInviteRepository.lockForInvitee(INVITE_ID, ALEX)).thenReturn(Optional.empty());

        assertRefused(() -> sessionInviteService.accept(INVITE_ID, ALEX, authentication()), SessionErrorCode.INVITE_NOT_FOUND);
    }
}
