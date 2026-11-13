package com.solesonic.service.session;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.a2a.A2AAgentSource;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.a2a.A2AParticipantMessage;
import com.solesonic.model.a2a.RegisteredAgent;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionMessageAccepted;
import com.solesonic.model.session.SessionMessageRequest;
import com.solesonic.model.session.SessionPrompt;
import com.solesonic.model.session.SessionPromptAnswer;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SessionTurn;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.prompt.PromptService;
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
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.service.session.SessionFixtures.AGENT;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
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
    private static final Authentication NO_AUTHENTICATION = null;
    private static final ChatMessage IN_FLIGHT_MESSAGE = new ChatMessage();

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
    private PromptService promptService;

    @Mock
    private UserLlmKeyService userLlmKeyService;

    @Mock
    private PushNotificationService pushNotificationService;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private SharedCommandPolicy sharedCommandPolicy;

    @Mock
    private com.solesonic.service.chat.ChatModelService chatModelService;

    private SessionMessageService sessionMessageService;
    private SharedSession sharedSession;

    @BeforeEach
    void setUp() {
        sessionMessageService = new SessionMessageService(sharedSessionService, sessionRunLock, redisStreamingChatService,
                redisStreamService, a2aAgentService, a2aAgentRegistry, promptService, userLlmKeyService, pushNotificationService,
                CONVERSATION_SETTINGS, chatAttachmentService, sharedCommandPolicy, chatModelService);

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
        when(a2aAgentService.delegateShared(any(), anyString(), any(), any())).thenReturn(Flux.empty());
    }

    private TurnPlan startedPlan() {
        ArgumentCaptor<TurnPlan> turnPlan = ArgumentCaptor.forClass(TurnPlan.class);
        verify(redisStreamingChatService).start(turnPlan.capture());
        return turnPlan.getValue();
    }

    private A2AParticipantMessage sentToTheAgent(TurnPlan turnPlan) {
        turnPlan.chunks().apply(IN_FLIGHT_MESSAGE).blockLast();

        ArgumentCaptor<A2AParticipantMessage> participantMessage = ArgumentCaptor.forClass(A2AParticipantMessage.class);
        verify(a2aAgentService).delegateShared(eq(CHAT_ID), eq(AGENT), participantMessage.capture(), any());
        return participantMessage.getValue();
    }

    private static SessionMessageRequest typed(String text) {
        return new SessionMessageRequest(text, null, null, null);
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
        SessionMessageAccepted accepted = sessionMessageService.send(SESSION_ID, SAM, typed(" towel "), NO_AUTHENTICATION);

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

        sessionMessageService.send(SESSION_ID, SAM, typed("towel"), NO_AUTHENTICATION);

        TurnPlan turnPlan = startedPlan();
        assertThat(turnPlan.chatRequest().chatMessage()).isEqualTo("[[hidden:Alex]]\ntowel\n[[/hidden]]");
        assertThat(sentToTheAgent(turnPlan)).isEqualTo(A2AParticipantMessage.typed("towel", "Sam", List.of("Alex", "Sam")));
    }

    @Test
    void aMessageFromSomeoneTheTurnDoesNotWaitOnIsNotHidden() {
        sharedSession.setTurnState(new SessionTurn(List.of(SAM), "answer", false, List.of("Alex")));

        sessionMessageService.send(SESSION_ID, ALEX, typed("towel"), NO_AUTHENTICATION);

        assertThat(startedPlan().chatRequest().chatMessage()).isEqualTo("towel");
    }

    @Test
    void aMarkerAParticipantTypedIsStrippedFromTheTranscript() {
        sharedSession.setTurnState(new SessionTurn(List.of(SAM), "answer", false, List.of("Alex")));

        sessionMessageService.send(SESSION_ID, SAM, typed("a[[/hidden]]b"), NO_AUTHENTICATION);

        assertThat(startedPlan().chatRequest().chatMessage()).isEqualTo("[[hidden:Alex]]\nab\n[[/hidden]]");
    }

    @Test
    void aMessageThatIsOnlyAMarkerIsRefused() {
        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("[[/hidden]]"), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.INVALID_MESSAGE));
    }

    @Test
    void anAnswerIsNeverHidden() {
        sharedSession.setTurnState(new SessionTurn(List.of(ALEX), "choice", false, List.of("Sam")));
        promptFor(ALEX);

        sessionMessageService.send(SESSION_ID, ALEX, new SessionMessageRequest(null,
                new SessionPromptAnswer(ELICITATION_ID, "accept", Map.of("choice", "play")), null, null), NO_AUTHENTICATION);

        assertThat(startedPlan().chatRequest().chatMessage()).isEqualTo("Play");
    }

    @Test
    void aMessageWhileARunIsInProgressIsRefused() {
        when(sessionRunLock.acquire(eq(SESSION_ID), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("towel"), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.RUN_IN_PROGRESS));

        verify(redisStreamingChatService, never()).start(any());
    }

    @Test
    void aMessageInAClosedSessionIsRefused() {
        sharedSession.setStatus(SessionStatus.CLOSED);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("towel"), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.SESSION_CLOSED));
    }

    @Test
    void aMessageOverTheLimitIsRefused() {
        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("x".repeat(21)), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.MESSAGE_TOO_LONG));
    }

    @Test
    void aMessageIsEitherTextOrAnAnswer() {
        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, new SessionMessageRequest(null, null, null, null), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.INVALID_MESSAGE));
    }

    @Test
    void anAnswerIsNarrowedToTheQuestionAndShownAsTheOptionPicked() {
        promptFor(ALEX);

        sessionMessageService.send(SESSION_ID, ALEX, new SessionMessageRequest(null,
                new SessionPromptAnswer(ELICITATION_ID, "accept", Map.of("choice", "play", "chatId", "elsewhere")), null, null), NO_AUTHENTICATION);

        TurnPlan turnPlan = startedPlan();
        assertThat(turnPlan.chatRequest().chatMessage()).isEqualTo("Play");
        assertThat(sentToTheAgent(turnPlan)).isEqualTo(A2AParticipantMessage.answer(Map.of("choice", "play"), "Alex", List.of("Alex", "Sam")));
    }

    @Test
    void anAnswerFromAParticipantTheQuestionIsNotForIsRefused() {
        promptFor(ALEX);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, new SessionMessageRequest(null,
                new SessionPromptAnswer(ELICITATION_ID, "accept", Map.of("choice", "play")), null, null), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.NOT_YOUR_TURN));

        verify(sessionRunLock, never()).acquire(any(), any(), any());
    }

    @Test
    void anAnswerToAQuestionThatHasBeenReplacedIsRefused() {
        promptFor(ALEX);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, ALEX, new SessionMessageRequest(null,
                new SessionPromptAnswer(UUID.randomUUID(), "accept", Map.of("choice", "play")), null, null), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.NOT_YOUR_TURN));
    }

    @Test
    void aRunThatCannotStartReleasesTheLock() {
        doReturn(Mono.error(new IllegalStateException("redis down"))).when(redisStreamingChatService).start(any());

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("towel"), NO_AUTHENTICATION))
                .isInstanceOf(IllegalStateException.class);

        verify(sessionRunLock).release(eq(SESSION_ID), any());
    }

    @Test
    void aFinishedRunPushesWhoeverTheSessionNowWaitsOnAndReleasesTheLock() {
        sessionMessageService.send(SESSION_ID, SAM, typed("towel"), NO_AUTHENTICATION);
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
        sessionMessageService.openingMessage(sharedSession, SAM, "new show");
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
        sessionMessageService.send(SESSION_ID, SAM, typed("towel"), NO_AUTHENTICATION);
        TurnPlan turnPlan = startedPlan();

        turnPlan.hooks().afterTerminal(turnPlan.runId(), TurnOutcome.FAILED);

        verify(pushNotificationService).push(SAM, new PushEvent.TurnFailed(CHAT_ID, turnPlan.runId()));
        verify(pushNotificationService, never()).push(eq(ALEX), any());
    }

    // ---- conversation mode

    private void aConversation() {
        sharedSession = conversationSession(SessionStatus.ACTIVE);
        List<com.solesonic.model.session.SessionParticipant> seated =
                List.of(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"), participant(KIM, 2, "Kim"));
        when(sharedSessionService.requireSeated(SESSION_ID, SAM)).thenReturn(sharedSession);
        when(sharedSessionService.requireSeated(SESSION_ID, ALEX)).thenReturn(sharedSession);
        when(sharedSessionService.participants(SESSION_ID)).thenReturn(seated);
        when(promptService.streamShared(any(), any(), any(), any(), any(), any(), any())).thenReturn(Flux.just("hi"));
    }

    @Test
    void aModelTheSendersKeyCannotUseIsRefusedBeforeAnyRunStartsNamingTheModel() {
        aConversation();
        when(chatModelService.unusableSelectedModel(SAM, CHAT_ID)).thenReturn(Optional.of("openai/gpt-4o"));

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("hello"), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class, sessionException -> {
                    assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.MODEL_NOT_AVAILABLE);
                    assertThat(sessionException.getMessage()).contains("openai/gpt-4o").contains("Pick another model");
                });

        verify(sessionRunLock, never()).acquire(any(), any(), any());
        verify(redisStreamingChatService, never()).start(any());
    }

    @Test
    void aSendOnAModelTheKeyCanUseGoesAhead() {
        aConversation();
        when(chatModelService.unusableSelectedModel(SAM, CHAT_ID)).thenReturn(Optional.empty());

        sessionMessageService.send(SESSION_ID, SAM, typed("hello"), NO_AUTHENTICATION);

        verify(redisStreamingChatService).start(any());
    }

    @Test
    void anAgentSessionNeverChecksTheModel() {
        sessionMessageService.send(SESSION_ID, SAM, typed("hello"), NO_AUTHENTICATION);

        verify(chatModelService, never()).unusableSelectedModel(any(), any());
    }

    private void assertRefused(SessionMessageRequest request, SessionErrorCode errorCode) {
        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, request, NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(errorCode));
        verify(sessionRunLock, never()).acquire(any(), any(), any());
        verify(redisStreamingChatService, never()).start(any());
    }

    @Test
    void aConversationMessageRunsAsItsSenderOnTheOwnersStreamThroughTheSharedRoute() {
        aConversation();
        Authentication authentication = mock(Authentication.class);

        SessionMessageAccepted accepted = sessionMessageService.send(SESSION_ID, SAM, typed(" hello all "), authentication);

        TurnPlan turnPlan = startedPlan();
        assertThat(accepted).isEqualTo(new SessionMessageAccepted(turnPlan.runId(), MESSAGE_ID));
        assertThat(turnPlan.chatId()).isEqualTo(CHAT_ID);
        assertThat(turnPlan.streamUserId()).isEqualTo(ALEX);
        assertThat(turnPlan.senderUserId()).isEqualTo(SAM);
        assertThat(turnPlan.senderName()).isEqualTo("Sam");
        assertThat(turnPlan.chatRequest().chatMessage()).isEqualTo("hello all");

        turnPlan.chunks().apply(IN_FLIGHT_MESSAGE).blockLast();

        ArgumentCaptor<com.solesonic.model.chat.ChatRequest> chatRequest = ArgumentCaptor.forClass(com.solesonic.model.chat.ChatRequest.class);
        verify(promptService).streamShared(eq(CHAT_ID), eq(SAM), eq("Sam"), eq(List.of("Alex", "Sam", "Kim")), chatRequest.capture(),
                same(IN_FLIGHT_MESSAGE), eq(authentication));
        assertThat(chatRequest.getValue().chatMessage()).isEqualTo("hello all");
        assertThat(chatRequest.getValue().commands()).isEmpty();
        assertThat(chatRequest.getValue().attachmentIds()).isEmpty();
        verify(a2aAgentService, never()).delegateShared(any(), any(), any(), any());
        verify(a2aAgentRegistry, never()).agent(any());
        verify(userLlmKeyService).requireKey(SAM);
    }

    @Test
    void theConversationRunLockUsesTheConversationDuration() {
        aConversation();

        sessionMessageService.send(SESSION_ID, SAM, typed("hello"), NO_AUTHENTICATION);

        verify(sessionRunLock).acquire(eq(SESSION_ID), any(), eq(CONVERSATION_SETTINGS.conversation().runLock()));
        verify(sessionRunLock).recordCursor(eq(SESSION_ID), eq("5-0"), eq(CONVERSATION_SETTINGS.conversation().runLock()));
    }

    @Test
    void textThatStartsWithASlashIsJustTextWhenNoCommandIsNamed() {
        aConversation();

        sessionMessageService.send(SESSION_ID, SAM, typed("/model other"), NO_AUTHENTICATION);

        TurnPlan turnPlan = startedPlan();
        assertThat(turnPlan.chatRequest().chatMessage()).isEqualTo("/model other");
        assertThat(turnPlan.chatRequest().commands()).isEmpty();
    }

    private static SessionMessageRequest sending(String text, Set<UUID> attachmentIds, Set<String> commands) {
        return new SessionMessageRequest(text, null, attachmentIds, commands);
    }

    @Test
    void theSendersOwnStagedAttachmentsRideTheRunAndTheTextMayBeEmpty() {
        aConversation();
        UUID image = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        Set<UUID> ids = Set.of(image, document);
        when(chatAttachmentService.allStagedFor(SAM, ids)).thenReturn(true);

        sessionMessageService.send(SESSION_ID, SAM, sending("   ", ids, null), NO_AUTHENTICATION);

        TurnPlan turnPlan = startedPlan();
        assertThat(turnPlan.senderUserId()).isEqualTo(SAM);
        assertThat(turnPlan.chatRequest().chatMessage()).isEmpty();
        assertThat(turnPlan.chatRequest().attachmentIds()).containsExactlyInAnyOrder(image, document);

        turnPlan.chunks().apply(IN_FLIGHT_MESSAGE).blockLast();
        ArgumentCaptor<com.solesonic.model.chat.ChatRequest> chatRequest = ArgumentCaptor.forClass(com.solesonic.model.chat.ChatRequest.class);
        verify(promptService).streamShared(eq(CHAT_ID), eq(SAM), eq("Sam"), any(), chatRequest.capture(), same(IN_FLIGHT_MESSAGE), any());
        assertThat(chatRequest.getValue().attachmentIds()).containsExactlyInAnyOrder(image, document);
    }

    @Test
    void attachmentsThatAreNotAllTheSendersStagedOnesAreRefused() {
        aConversation();
        Set<UUID> ids = Set.of(UUID.randomUUID());
        when(chatAttachmentService.allStagedFor(SAM, ids)).thenReturn(false);

        assertRefused(sending("look", ids, null), SessionErrorCode.INVALID_MESSAGE);
    }

    @Test
    void moreAttachmentsThanAMessageMayCarryAreRefusedBeforeTheyAreChecked() {
        aConversation();
        Set<UUID> ids = Set.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertRefused(sending("look", ids, null), SessionErrorCode.INVALID_MESSAGE);
        verify(chatAttachmentService, never()).allStagedFor(any(), any());
    }

    @Test
    void fourAttachmentsAreAccepted() {
        aConversation();
        Set<UUID> ids = Set.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(chatAttachmentService.allStagedFor(SAM, ids)).thenReturn(true);

        sessionMessageService.send(SESSION_ID, SAM, sending("look", ids, null), NO_AUTHENTICATION);

        assertThat(startedPlan().chatRequest().attachmentIds()).hasSize(4);
    }

    @Test
    void anAllowedCommandRidesTheRunAndTheTextMayBeEmpty() {
        aConversation();
        when(sharedCommandPolicy.require(Set.of("web_search"))).thenReturn(Set.of("web_search"));

        sessionMessageService.send(SESSION_ID, SAM, sending("", null, Set.of("web_search")), NO_AUTHENTICATION);

        TurnPlan turnPlan = startedPlan();
        assertThat(turnPlan.chatRequest().chatMessage()).isEmpty();
        assertThat(turnPlan.chatRequest().commands()).containsExactly("web_search");
    }

    @Test
    void aRefusedCommandRefusesTheMessageAndNamesTheCommandWithoutStartingARun() {
        aConversation();
        when(sharedCommandPolicy.require(Set.of("feud")))
                .thenThrow(new SessionException(SessionErrorCode.COMMAND_NOT_ALLOWED, "The command /feud is not available in a shared chat."));

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, sending("hi", null, Set.of("feud")), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(SessionErrorCode.COMMAND_NOT_ALLOWED);
                    assertThat(exception.getMessage()).contains("/feud");
                });
        verify(redisStreamingChatService, never()).start(any());
    }

    @Test
    void theModelCommandIsAppliedThroughTheModelPickerNeverSentAsAMessage() {
        aConversation();
        when(sharedCommandPolicy.require(Set.of("model"))).thenReturn(Set.of("model"));

        assertRefused(sending("gpt", null, Set.of("model")), SessionErrorCode.INVALID_MESSAGE);
    }

    @Test
    void aMessageWithNeitherTextNorAttachmentsNorCommandsIsStillEmpty() {
        aConversation();

        assertRefused(sending("  ", Set.of(), Set.of()), SessionErrorCode.INVALID_MESSAGE);
    }

    @Test
    void aMessageWithOnlyAHiddenMarkerAndAnAttachmentIsAcceptedAsAttachmentsOnly() {
        aConversation();
        Set<UUID> ids = Set.of(UUID.randomUUID());
        when(chatAttachmentService.allStagedFor(SAM, ids)).thenReturn(true);

        sessionMessageService.send(SESSION_ID, SAM, sending("[[/hidden]]", ids, null), NO_AUTHENTICATION);

        assertThat(startedPlan().chatRequest().chatMessage()).isEmpty();
    }

    @Test
    void anAgentSessionTakesNeitherAttachmentsNorCommands() {
        assertRefused(sending("hi", Set.of(UUID.randomUUID()), null), SessionErrorCode.INVALID_MESSAGE);
        assertRefused(sending("hi", null, Set.of("web_search")), SessionErrorCode.INVALID_MESSAGE);
    }

    @Test
    void aSlashInTheMiddleOfAMessageIsFine() {
        aConversation();

        sessionMessageService.send(SESSION_ID, SAM, typed("and/or"), NO_AUTHENTICATION);

        assertThat(startedPlan().chatRequest().chatMessage()).isEqualTo("and/or");
    }

    @Test
    void aConversationRefusesAnAnswerToAQuestion() {
        aConversation();

        assertRefused(new SessionMessageRequest(null, new SessionPromptAnswer(ELICITATION_ID, "accept", Map.of("choice", "play")), null, null),
                SessionErrorCode.INVALID_MESSAGE);
    }

    @Test
    void aConversationMessageIsLimitedToTheConversationLength() {
        aConversation();

        assertRefused(typed("x".repeat(51)), SessionErrorCode.MESSAGE_TOO_LONG);
    }

    @Test
    void aConversationMessageAtTheLimitIsAccepted() {
        aConversation();

        sessionMessageService.send(SESSION_ID, SAM, typed("x".repeat(50)), NO_AUTHENTICATION);

        assertThat(startedPlan().chatRequest().chatMessage()).hasSize(50);
    }

    @Test
    void anEmptyConversationMessageIsRefused() {
        aConversation();

        assertRefused(typed("   "), SessionErrorCode.INVALID_MESSAGE);
        assertRefused(new SessionMessageRequest(null, null, null, null), SessionErrorCode.INVALID_MESSAGE);
        assertRefused(null, SessionErrorCode.INVALID_MESSAGE);
    }

    @Test
    void hiddenMarkersAreStrippedFromAConversationMessage() {
        aConversation();

        sessionMessageService.send(SESSION_ID, SAM, typed("a[[/hidden]]b"), NO_AUTHENTICATION);

        assertThat(startedPlan().chatRequest().chatMessage()).isEqualTo("ab");
    }

    @Test
    void aMessageInAClosedConversationIsRefused() {
        aConversation();
        sharedSession.setStatus(SessionStatus.CLOSED);

        assertRefused(typed("hello"), SessionErrorCode.SESSION_CLOSED);
    }

    @Test
    void aConversationMessageWhileARunIsInProgressIsRefused() {
        aConversation();
        when(sessionRunLock.acquire(eq(SESSION_ID), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> sessionMessageService.send(SESSION_ID, SAM, typed("hello"), NO_AUTHENTICATION))
                .isInstanceOfSatisfying(SessionException.class,
                        sessionException -> assertThat(sessionException.getErrorCode()).isEqualTo(SessionErrorCode.RUN_IN_PROGRESS));
        verify(redisStreamingChatService, never()).start(any());
    }

    private TurnPlan startedConversationPlan() {
        aConversation();
        sessionMessageService.send(SESSION_ID, SAM, typed("hello"), NO_AUTHENTICATION);
        return startedPlan();
    }

    private void finish(TurnPlan turnPlan, TurnOutcome outcome) {
        SharedSessionSummary summary = new SharedSessionSummary(SESSION_ID, CHAT_ID, SessionMode.CONVERSATION, "Chat", ALEX, null,
                SessionStatus.ACTIVE, null, List.of(), List.of(), null, null, null, false, NOW, NOW);
        when(sharedSessionService.applyOutcome(eq(SESSION_ID), eq(turnPlan.runId()), eq(outcome), any())).thenReturn(summary);
        when(sharedSessionService.publishState(CHAT_ID, ALEX, summary)).thenReturn(Mono.empty());

        turnPlan.hooks().beforeTerminal(turnPlan.runId(), outcome).block();
        turnPlan.hooks().afterTerminal(turnPlan.runId(), outcome);
        turnPlan.hooks().finished(turnPlan.runId());

        verify(sharedSessionService).publishState(CHAT_ID, ALEX, summary);
        verify(sessionRunLock).release(SESSION_ID, turnPlan.runId());
    }

    @Test
    void aCompletedConversationRunPushesEveryOtherSeatedParticipantButNotTheSender() {
        TurnPlan turnPlan = startedConversationPlan();

        finish(turnPlan, TurnOutcome.COMPLETED);

        PushEvent.SharedChatMessage event = new PushEvent.SharedChatMessage(CHAT_ID, SESSION_ID, turnPlan.runId());
        verify(pushNotificationService).push(ALEX, event);
        verify(pushNotificationService).push(KIM, event);
        verify(pushNotificationService, never()).push(eq(SAM), any());
    }

    @Test
    void aCompletedConversationRunDoesNotPushSomeoneWhoHasLeft() {
        TurnPlan turnPlan = startedConversationPlan();
        com.solesonic.model.session.SessionParticipant kim = participant(KIM, 2, "Kim");
        kim.setLeftAt(NOW);
        when(sharedSessionService.participants(SESSION_ID))
                .thenReturn(List.of(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"), kim));

        finish(turnPlan, TurnOutcome.COMPLETED);

        verify(pushNotificationService).push(eq(ALEX), any(PushEvent.SharedChatMessage.class));
        verify(pushNotificationService, never()).push(eq(KIM), any());
        verify(pushNotificationService, never()).push(eq(SAM), any());
    }

    @Test
    void aCancelledConversationRunPushesNobody() {
        TurnPlan turnPlan = startedConversationPlan();

        finish(turnPlan, TurnOutcome.CANCELLED);

        verify(pushNotificationService, never()).push(any(), any());
    }

    @Test
    void aConversationIsNamedToTheModelByTheParticipantsStillSeated() {
        aConversation();
        com.solesonic.model.session.SessionParticipant kim = participant(KIM, 2, "Kim");
        kim.setLeftAt(NOW);
        when(sharedSessionService.participants(SESSION_ID))
                .thenReturn(List.of(participant(ALEX, 0, "Alex"), participant(SAM, 1, "Sam"), kim));

        sessionMessageService.send(SESSION_ID, SAM, typed("hello"), NO_AUTHENTICATION);
        startedPlan().chunks().apply(IN_FLIGHT_MESSAGE).blockLast();

        verify(promptService).streamShared(eq(CHAT_ID), eq(SAM), eq("Sam"), eq(List.of("Alex", "Sam")), any(), same(IN_FLIGHT_MESSAGE), any());
    }

    @Test
    void aFailedConversationRunIsPushedToItsSender() {
        aConversation();
        sessionMessageService.send(SESSION_ID, SAM, typed("hello"), NO_AUTHENTICATION);
        TurnPlan turnPlan = startedPlan();

        turnPlan.hooks().afterTerminal(turnPlan.runId(), TurnOutcome.FAILED);

        verify(pushNotificationService).push(SAM, new PushEvent.TurnFailed(CHAT_ID, turnPlan.runId()));
    }

    private static SharedSessionSummary summary(SessionTurn turn) {
        return new SharedSessionSummary(SESSION_ID, CHAT_ID, SessionMode.AGENT, null, ALEX, AGENT, SessionStatus.ACTIVE, null, List.of(), List.of(),
                turn, null, null, false, NOW, NOW);
    }
}
