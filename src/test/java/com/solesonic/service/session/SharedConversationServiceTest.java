package com.solesonic.service.session;

import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatKind;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.security.SecurityEvent;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionInvite;
import com.solesonic.model.session.SessionInviteStatus;
import com.solesonic.model.session.SessionInviteSummary;
import com.solesonic.model.session.SessionInvitesRequest;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SharedConversationRequest;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.model.user.UserProfile;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.session.SessionInviteRepository;
import com.solesonic.repository.session.SessionParticipantRepository;
import com.solesonic.repository.session.SharedSessionRepository;
import com.solesonic.repository.user.UserProfileRepository;
import com.solesonic.service.chat.ChatModelSelection;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.redis.ActiveStreamTracker;
import com.solesonic.service.security.SecurityEventLogger;
import com.solesonic.service.user.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.session.SessionFixtures.ALEX;
import static com.solesonic.service.session.SessionFixtures.CHAT_ID;
import static com.solesonic.service.session.SessionFixtures.CONVERSATION_SETTINGS;
import static com.solesonic.service.session.SessionFixtures.KIM;
import static com.solesonic.service.session.SessionFixtures.NOW;
import static com.solesonic.service.session.SessionFixtures.SAM;
import static com.solesonic.service.session.SessionFixtures.SESSION_ID;
import static com.solesonic.service.session.SessionFixtures.conversationSession;
import static com.solesonic.service.session.SessionFixtures.participant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharedConversationServiceTest {
    private static final UUID SOURCE_CHAT = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID NEW_CHAT = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Mock
    private UserLlmKeyService userLlmKeyService;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private ChatService chatService;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private ActiveStreamTracker activeStreamTracker;
    @Mock
    private ChatModelSelection chatModelSelection;
    @Mock
    private SharedSessionRepository sharedSessionRepository;
    @Mock
    private SessionParticipantRepository sessionParticipantRepository;
    @Mock
    private SessionInviteRepository sessionInviteRepository;
    @Mock
    private SessionInviteService sessionInviteService;
    @Mock
    private SharedSessionService sharedSessionService;
    @Mock
    private PushNotificationService pushNotificationService;
    @Mock
    private SecurityEventLogger securityEventLogger;

    private SharedConversationService sharedConversationService;

    @BeforeEach
    void setUp() {
        sharedConversationService = service(CONVERSATION_SETTINGS);

        when(userProfileRepository.findActiveUserIdByUsername("sam")).thenReturn(Optional.of(SAM));
        when(userProfileRepository.findActiveUserIdByUsername("kim")).thenReturn(Optional.of(KIM));
        when(userProfileRepository.findActiveUserIdByUsername("alex")).thenReturn(Optional.of(ALEX));
        when(userProfileRepository.findActiveUserIdByUsername("dee")).thenReturn(Optional.of(UUID.randomUUID()));
        when(userProfileRepository.findActiveUserIdByUsername("lou")).thenReturn(Optional.of(UUID.randomUUID()));
        when(userProfileService.profile(ALEX)).thenReturn(Optional.of(new UserProfile(ALEX, "alex", "a@example.com", "Alex")));
        when(userProfileService.profiles(any())).thenReturn(Map.of());
        when(chatService.requireOwned(SOURCE_CHAT)).thenReturn(ALEX);
        when(chatRepository.findByIdAndUserId(SOURCE_CHAT, ALEX)).thenReturn(Optional.of(chat(SOURCE_CHAT, "Trip plans", ChatKind.CONVERSATION)));
        when(chatRepository.save(any(Chat.class))).thenAnswer(invocation -> {
            Chat chat = invocation.getArgument(0);
            chat.setId(NEW_CHAT);
            return chat;
        });
        when(activeStreamTracker.get(ALEX)).thenReturn(Mono.empty());
        when(chatModelSelection.get(SOURCE_CHAT)).thenReturn(Mono.empty());
        when(chatModelSelection.set(any(), anyString())).thenReturn(Mono.empty());
        when(sharedSessionRepository.save(any(SharedSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionParticipantRepository.save(any(SessionParticipant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionInviteRepository.save(any(SessionInvite.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionInviteRepository.findBySessionIdAndStatus(any(), eq(SessionInviteStatus.PENDING))).thenReturn(List.of());
        when(sharedSessionService.summary(any(SharedSession.class), any(), eq(ALEX), eq(false))).thenReturn(summary());
    }

    private SharedConversationService service(SharedSessionSettings settings) {
        return new SharedConversationService(settings, userLlmKeyService, userProfileRepository, userProfileService, chatService,
                chatRepository, chatMessageRepository, activeStreamTracker, chatModelSelection, sharedSessionRepository,
                sessionParticipantRepository, sessionInviteRepository, sessionInviteService, sharedSessionService,
                pushNotificationService, securityEventLogger, Clock.fixed(NOW.toInstant(), NOW.getZone()));
    }

    private static Chat chat(UUID id, String name, ChatKind kind) {
        Chat chat = new Chat();
        chat.setId(id);
        chat.setUserId(ALEX);
        chat.setName(name);
        chat.setKind(kind);
        return chat;
    }

    private static SharedSessionSummary summary() {
        return new SharedSessionSummary(SESSION_ID, NEW_CHAT, SessionMode.CONVERSATION, "Trip plans", ALEX, null, SessionStatus.ACTIVE,
                List.of(), List.of(), null, null, null, false, NOW, NOW);
    }

    private static SessionException refused(Runnable action) {
        try {
            action.run();
        } catch (SessionException sessionException) {
            return sessionException;
        }
        throw new AssertionError("not refused");
    }

    private void assertNothingWritten() {
        verify(chatRepository, never()).save(any());
        verify(chatMessageRepository, never()).forkRecent(any(), any(), any(), anyInt());
        verify(sharedSessionRepository, never()).save(any());
        verify(sessionParticipantRepository, never()).save(any());
        verify(sessionInviteRepository, never()).save(any());
        verifyNoInteractions(pushNotificationService);
    }

    private static SharedConversationRequest share(String... usernames) {
        return new SharedConversationRequest(SOURCE_CHAT, List.of(usernames), null);
    }

    @Test
    void sharingCanBeSwitchedOff() {
        sharedConversationService = service(new SharedSessionSettings(java.util.Set.of(), java.time.Duration.ofHours(1), 5, 20, 40,
                java.time.Duration.ofSeconds(600), java.time.Duration.ofDays(7),
                new SharedSessionSettings.Conversation(false, 4, 50, java.time.Duration.ofSeconds(2100), java.util.Set.of(), 3)));

        assertThat(refused(() -> sharedConversationService.create(ALEX, share("sam"))).getErrorCode())
                .isEqualTo(SessionErrorCode.SESSION_NOT_FOUND);
        assertNothingWritten();
    }

    @Test
    void anOwnerWithNoModelKeyCannotShare() {
        when(userLlmKeyService.requireKey(ALEX)).thenThrow(new LlmKeyMissingException());

        assertThatThrownBy(() -> sharedConversationService.create(ALEX, share("sam"))).isInstanceOf(LlmKeyMissingException.class);
        assertNothingWritten();
    }

    @Test
    void everyUnknownUsernameIsReportedAndNothingIsWritten() {
        SessionException refused = refused(() -> sharedConversationService.create(ALEX, share("sam", "ghost", "phantom")));

        assertThat(refused.getErrorCode()).isEqualTo(SessionErrorCode.PARTICIPANT_NOT_FOUND);
        assertThat(refused.getUsernames()).containsExactly("ghost", "phantom");
        assertNothingWritten();
    }

    @Test
    void usernamesAreStrippedDeduplicatedIgnoringCaseAndTheOwnerIsDropped() {
        sharedConversationService.create(ALEX, share(" sam ", "SAM", "alex"));

        ArgumentCaptor<SessionInvite> invites = ArgumentCaptor.forClass(SessionInvite.class);
        verify(sessionInviteRepository, times(1)).save(invites.capture());
        assertThat(invites.getValue().getInviteeUserId()).isEqualTo(SAM);
    }

    @Test
    void aRequestThatNamesNobodyButTheOwnerIsInvalid() {
        assertThat(refused(() -> sharedConversationService.create(ALEX, share("alex"))).getErrorCode())
                .isEqualTo(SessionErrorCode.INVALID_INVITE);
        assertThat(refused(() -> sharedConversationService.create(ALEX, new SharedConversationRequest(null, List.of(), null))).getErrorCode())
                .isEqualTo(SessionErrorCode.INVALID_INVITE);
        assertThat(refused(() -> sharedConversationService.create(ALEX, new SharedConversationRequest(null, null, null))).getErrorCode())
                .isEqualTo(SessionErrorCode.INVALID_INVITE);
        assertNothingWritten();
    }

    @Test
    void theOwnerPlusTheInviteesCannotExceedTheMaximum() {
        assertThat(refused(() -> sharedConversationService.create(ALEX, share("sam", "kim", "dee", "lou"))).getErrorCode())
                .isEqualTo(SessionErrorCode.TOO_MANY_PARTICIPANTS);
        assertNothingWritten();
    }

    @Test
    void theInvitesWaitingAcrossAllTheOwnersSessionsAreCapped() {
        sharedConversationService = service(SessionFixtures.settings(8, 5));
        when(sessionInviteRepository.countByInviterUserIdAndStatus(ALEX, SessionInviteStatus.PENDING)).thenReturn(4L);

        // 4 already waiting + 2 new = 6 > 5
        assertThat(refused(() -> sharedConversationService.create(ALEX, share("sam", "kim"))).getErrorCode())
                .isEqualTo(SessionErrorCode.TOO_MANY_INVITES);
        assertNothingWritten();
    }

    @Test
    void invitesExactlyUpToTheCapAreAllowed() {
        sharedConversationService = service(SessionFixtures.settings(8, 5));
        when(sessionInviteRepository.countByInviterUserIdAndStatus(ALEX, SessionInviteStatus.PENDING)).thenReturn(3L);

        sharedConversationService.create(ALEX, share("sam", "kim"));

        verify(sessionInviteRepository, times(2)).save(any(SessionInvite.class));
    }

    @Test
    void pastDueInvitesAreExpiredBeforeTheyAreCounted() {
        sharedConversationService.create(ALEX, share("sam"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sessionInviteRepository);
        order.verify(sessionInviteRepository).expireDueFor(ALEX, NOW);
        order.verify(sessionInviteRepository).countByInviterUserIdAndStatus(ALEX, SessionInviteStatus.PENDING);
    }

    @Test
    void anOwnerAtTheActiveSessionLimitCannotShareAnother() {
        when(sharedSessionRepository.countActiveConversationsOwnedBy(ALEX)).thenReturn((long) CONVERSATION_SETTINGS.conversation().maxActiveSessions());

        assertThat(refused(() -> sharedConversationService.create(ALEX, share("sam"))).getErrorCode())
                .isEqualTo(SessionErrorCode.SESSION_LIMIT_REACHED);
        assertNothingWritten();
    }

    @Test
    void anOwnerJustUnderTheActiveSessionLimitCanShare() {
        when(sharedSessionRepository.countActiveConversationsOwnedBy(ALEX)).thenReturn((long) CONVERSATION_SETTINGS.conversation().maxActiveSessions() - 1);

        sharedConversationService.create(ALEX, share("sam"));

        verify(sharedSessionRepository).save(any(SharedSession.class));
    }

    @Test
    void aSessionNameIsCutToOneHundredCharacters() {
        sharedConversationService.create(ALEX, new SharedConversationRequest(SOURCE_CHAT, List.of("sam"), "x".repeat(300)));

        ArgumentCaptor<Chat> chat = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).save(chat.capture());
        assertThat(chat.getValue().getName()).hasSize(100);
    }

    @Test
    void aChatThatIsNotTheCallersOrIsSharedAlreadyIsNotFound() {
        when(chatService.requireOwned(SOURCE_CHAT)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> sharedConversationService.create(ALEX, share("sam")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertNothingWritten();
    }

    @Test
    void aChatThatIsMidRunCannotBeShared() {
        when(activeStreamTracker.get(ALEX)).thenReturn(Mono.just(SOURCE_CHAT));

        assertThat(refused(() -> sharedConversationService.create(ALEX, share("sam"))).getErrorCode())
                .isEqualTo(SessionErrorCode.CHAT_STREAMING);
        assertNothingWritten();
    }

    @Test
    void aStreamInAnotherChatDoesNotBlockSharing() {
        when(activeStreamTracker.get(ALEX)).thenReturn(Mono.just(UUID.randomUUID()));

        sharedConversationService.create(ALEX, share("sam"));

        verify(chatMessageRepository).forkRecent(SOURCE_CHAT, NEW_CHAT, ALEX, 3);
    }

    @Test
    void theChatIsForkedIntoANewSharedChatOwnedByTheOwnerWithTheSourcesNameAndModel() {
        when(chatModelSelection.get(SOURCE_CHAT)).thenReturn(Mono.just("big-model"));

        sharedConversationService.create(ALEX, share("sam"));

        ArgumentCaptor<Chat> chat = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).save(chat.capture());
        assertThat(chat.getValue().getKind()).isEqualTo(ChatKind.SHARED);
        assertThat(chat.getValue().getUserId()).isEqualTo(ALEX);
        assertThat(chat.getValue().getName()).isEqualTo("Trip plans");
        verify(chatMessageRepository).forkRecent(SOURCE_CHAT, NEW_CHAT, ALEX, 3);
        verify(chatModelSelection).set(NEW_CHAT, "big-model");
    }

    @Test
    void aSourceWithoutASelectedModelLeavesTheNewChatOnTheDefault() {
        sharedConversationService.create(ALEX, share("sam"));

        verify(chatModelSelection, never()).set(any(), anyString());
    }

    @Test
    void aGivenNameWinsOverTheSourcesAndABlankOneDoesNot() {
        sharedConversationService.create(ALEX, new SharedConversationRequest(SOURCE_CHAT, List.of("sam"), "  Our trip "));
        sharedConversationService.create(ALEX, new SharedConversationRequest(SOURCE_CHAT, List.of("sam"), "   "));

        ArgumentCaptor<Chat> chats = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository, times(2)).save(chats.capture());
        assertThat(chats.getAllValues().get(0).getName()).isEqualTo("Our trip");
        assertThat(chats.getAllValues().get(1).getName()).isEqualTo("Trip plans");
    }

    @Test
    void startingWithoutASourceCreatesAnEmptyChat() {
        sharedConversationService.create(ALEX, new SharedConversationRequest(null, List.of("sam"), "Fresh"));

        verify(chatService, never()).requireOwned(any());
        verify(chatMessageRepository, never()).forkRecent(any(), any(), any(), anyInt());
        ArgumentCaptor<Chat> chat = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).save(chat.capture());
        assertThat(chat.getValue().getName()).isEqualTo("Fresh");
    }

    @Test
    void theSessionIsAConversationWithTheOwnerInSeatZeroAndOnePendingInvitePerInvitee() {
        SharedSessionSummary created = sharedConversationService.create(ALEX, share("sam", "kim"));

        assertThat(created).isEqualTo(summary());

        ArgumentCaptor<SharedSession> sessions = ArgumentCaptor.forClass(SharedSession.class);
        verify(sharedSessionRepository).save(sessions.capture());
        SharedSession saved = sessions.getValue();
        assertThat(saved.getMode()).isEqualTo(SessionMode.CONVERSATION);
        assertThat(saved.getAgentName()).isNull();
        assertThat(saved.getChatId()).isEqualTo(NEW_CHAT);
        assertThat(saved.getSourceChatId()).isEqualTo(SOURCE_CHAT);
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.ACTIVE);

        ArgumentCaptor<SessionParticipant> participants = ArgumentCaptor.forClass(SessionParticipant.class);
        verify(sessionParticipantRepository).save(participants.capture());
        assertThat(participants.getValue().getUserId()).isEqualTo(ALEX);
        assertThat(participants.getValue().getSeat()).isZero();
        assertThat(participants.getValue().getName()).isEqualTo("Alex");
        assertThat(participants.getValue().getSessionId()).isEqualTo(saved.getId());

        ArgumentCaptor<SessionInvite> invites = ArgumentCaptor.forClass(SessionInvite.class);
        verify(sessionInviteRepository, times(2)).save(invites.capture());
        assertThat(invites.getAllValues()).allSatisfy(invite -> {
            assertThat(invite.getSessionId()).isEqualTo(saved.getId());
            assertThat(invite.getAgentName()).isNull();
            assertThat(invite.getInviterUserId()).isEqualTo(ALEX);
            assertThat(invite.getStatus()).isEqualTo(SessionInviteStatus.PENDING);
            assertThat(invite.getExpires()).isEqualTo(NOW.plusHours(24));
        });
        assertThat(invites.getAllValues()).extracting(SessionInvite::getInviteeUserId).containsExactly(SAM, KIM);

        for (SessionInvite invite : invites.getAllValues()) {
            verify(pushNotificationService).push(invite.getInviteeUserId(), new PushEvent.SessionInvited(invite.getId()));
        }
        verify(securityEventLogger).audit(SecurityEvent.SESSION_SHARED, ALEX, saved.getId(), 2);
    }

    // invite(...)

    private SharedSession open;

    private void ownerOfASession(SessionStatus status, SessionParticipant... seated) {
        open = conversationSession(status);
        when(sharedSessionService.requireSeated(SESSION_ID, ALEX)).thenReturn(open);
        when(sharedSessionRepository.lockById(SESSION_ID)).thenReturn(Optional.of(open));
        when(sharedSessionService.participants(SESSION_ID)).thenReturn(List.of(seated));
    }

    private static SessionParticipant seat(UUID userId, int seat, String name) {
        return participant(userId, seat, name);
    }

    private static SessionInvitesRequest add(String... usernames) {
        return new SessionInvitesRequest(List.of(usernames));
    }

    @Test
    void anOwnerAddsPeopleToTheirSession() {
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"), seat(SAM, 1, "Sam"));
        SessionInviteSummary inviteSummary = mockSummary();
        when(sessionInviteService.summaries(any())).thenReturn(List.of(inviteSummary));

        List<SessionInviteSummary> invited = sharedConversationService.invite(SESSION_ID, ALEX, add("kim"));

        assertThat(invited).containsExactly(inviteSummary);
        ArgumentCaptor<SessionInvite> saved = ArgumentCaptor.forClass(SessionInvite.class);
        verify(sessionInviteRepository).save(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getValue().getInviteeUserId()).isEqualTo(KIM);
        assertThat(saved.getValue().getAgentName()).isNull();
        verify(pushNotificationService).push(KIM, new PushEvent.SessionInvited(saved.getValue().getId()));
        verify(securityEventLogger).audit(SecurityEvent.SESSION_INVITED, ALEX, SESSION_ID, 1);
    }

    private static SessionInviteSummary mockSummary() {
        return new SessionInviteSummary(UUID.randomUUID(), ALEX, "alex", "Alex", KIM, "kim", "Kim", SessionMode.CONVERSATION, "Trip plans",
                null, null, SessionInviteStatus.PENDING, SESSION_ID, NOW, NOW.plusHours(24));
    }

    @Test
    void someoneWhoDoesNotTakePartGetsTheSessionNotFound() {
        when(sharedSessionService.requireSeated(SESSION_ID, KIM)).thenThrow(SharedSessionService.sessionNotFound());

        assertThat(refused(() -> sharedConversationService.invite(SESSION_ID, KIM, add("sam"))).getErrorCode())
                .isEqualTo(SessionErrorCode.SESSION_NOT_FOUND);
        verify(sessionInviteRepository, never()).save(any());
    }

    @Test
    void aParticipantWhoDoesNotOwnTheSessionIsRefusedAsNotTheOwner() {
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"), seat(SAM, 1, "Sam"));
        when(sharedSessionService.requireSeated(SESSION_ID, SAM)).thenReturn(open);

        assertThat(refused(() -> sharedConversationService.invite(SESSION_ID, SAM, add("kim"))).getErrorCode())
                .isEqualTo(SessionErrorCode.NOT_SESSION_OWNER);
        verify(sessionInviteRepository, never()).save(any());
    }

    @Test
    void onlyAConversationTakesMorePeople() {
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"), seat(SAM, 1, "Sam"));
        open.setMode(SessionMode.AGENT);
        open.setAgentName("feud");

        assertThat(refused(() -> sharedConversationService.invite(SESSION_ID, ALEX, add("kim"))).getErrorCode())
                .isEqualTo(SessionErrorCode.INVALID_INVITE);
    }

    @Test
    void aSessionThatIsOverTakesNoOne() {
        ownerOfASession(SessionStatus.CLOSED, seat(ALEX, 0, "Alex"));

        assertThat(refused(() -> sharedConversationService.invite(SESSION_ID, ALEX, add("kim"))).getErrorCode())
                .isEqualTo(SessionErrorCode.SESSION_CLOSED);
    }

    @Test
    void someoneAlreadySeatedIsRefusedByName() {
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"), seat(SAM, 1, "Sam"));

        SessionException refused = refused(() -> sharedConversationService.invite(SESSION_ID, ALEX, add("sam", "kim")));

        assertThat(refused.getErrorCode()).isEqualTo(SessionErrorCode.INVALID_INVITE);
        assertThat(refused.getUsernames()).containsExactly("sam");
        verify(sessionInviteRepository, never()).save(any());
    }

    @Test
    void someoneWhoLeftCanBeInvitedAgain() {
        SessionParticipant left = seat(SAM, 1, "Sam");
        left.setLeftAt(NOW);
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"), left);
        when(sessionInviteService.summaries(any())).thenReturn(List.of());

        sharedConversationService.invite(SESSION_ID, ALEX, add("sam"));

        verify(sessionInviteRepository).save(any(SessionInvite.class));
    }

    @Test
    void anOwnerAddingPeopleStillCountsEveryInviteWaitingAcrossTheirSessions() {
        sharedConversationService = service(SessionFixtures.settings(8, 3));
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"));
        when(sessionInviteRepository.countByInviterUserIdAndStatus(ALEX, SessionInviteStatus.PENDING)).thenReturn(2L);

        assertThat(refused(() -> sharedConversationService.invite(SESSION_ID, ALEX, add("kim", "sam"))).getErrorCode())
                .isEqualTo(SessionErrorCode.TOO_MANY_INVITES);
        verify(sessionInviteRepository, never()).save(any());
    }

    @Test
    void pastDueInvitesOfTheSessionAreExpiredBeforeTheyAreCountedOrReused() {
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"));
        when(sessionInviteService.summaries(any())).thenReturn(List.of(mockSummary()));

        sharedConversationService.invite(SESSION_ID, ALEX, add("kim"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sessionInviteRepository);
        order.verify(sessionInviteRepository).expireDueFor(ALEX, NOW);
        order.verify(sessionInviteRepository).findBySessionIdAndStatus(SESSION_ID, SessionInviteStatus.PENDING);
        order.verify(sessionInviteRepository).save(any(SessionInvite.class));
    }

    @Test
    void someoneWhoseInviteIsStillPendingIsNotInvitedTwice() {
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"));
        SessionInvite waiting = new SessionInvite();
        waiting.setId(UUID.randomUUID());
        waiting.setInviteeUserId(KIM);
        waiting.setSessionId(SESSION_ID);
        waiting.setStatus(SessionInviteStatus.PENDING);
        when(sessionInviteRepository.findBySessionIdAndStatus(SESSION_ID, SessionInviteStatus.PENDING)).thenReturn(List.of(waiting));
        when(sessionInviteService.summaries(List.of(waiting))).thenReturn(List.of(mockSummary()));

        List<SessionInviteSummary> invited = sharedConversationService.invite(SESSION_ID, ALEX, add("kim"));

        assertThat(invited).hasSize(1);
        verify(sessionInviteRepository, never()).save(any());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void seatedPlusPendingPlusNewCannotExceedTheMaximum() {
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"), seat(SAM, 1, "Sam"));
        SessionInvite waiting = new SessionInvite();
        waiting.setInviteeUserId(KIM);
        waiting.setSessionId(SESSION_ID);
        when(sessionInviteRepository.findBySessionIdAndStatus(SESSION_ID, SessionInviteStatus.PENDING)).thenReturn(List.of(waiting));

        // 2 seated + 1 pending + 2 new = 5 > 4
        assertThat(refused(() -> sharedConversationService.invite(SESSION_ID, ALEX, add("dee", "lou"))).getErrorCode())
                .isEqualTo(SessionErrorCode.TOO_MANY_PARTICIPANTS);
        verify(sessionInviteRepository, never()).save(any());
    }

    @Test
    void unknownUsernamesAreAllReportedWhenInviting() {
        ownerOfASession(SessionStatus.ACTIVE, seat(ALEX, 0, "Alex"));

        SessionException refused = refused(() -> sharedConversationService.invite(SESSION_ID, ALEX, add("ghost", "kim", "phantom")));

        assertThat(refused.getErrorCode()).isEqualTo(SessionErrorCode.PARTICIPANT_NOT_FOUND);
        assertThat(refused.getUsernames()).containsExactly("ghost", "phantom");
        verify(sessionInviteRepository, never()).save(any());
    }
}
