package com.solesonic.service.chat;

import com.solesonic.config.chat.ConversationAdvisors;
import com.solesonic.config.chat.SessionMemoryConfig;
import com.solesonic.model.chat.SessionTurnRecord;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.chat.history.HistoryEntry;
import com.solesonic.model.chat.history.SessionLinks;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SharedSession;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.session.SessionParticipantRepository;
import com.solesonic.repository.session.SharedSessionRepository;
import com.solesonic.service.image.GeneratedImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The model's session against the real session service, over an in-memory store, with the user's
 * history held in a list that stands in for {@code chat_message}: what is copied in, once, in order, as
 * far back as the window; how the view is bounded; and what the end of a turn writes back.
 */
class ConversationSessionServiceTest {

    private static final int TRIGGER_TURNS = 4;
    private static final int KEEP_TURNS = 2;
    private static final int TOKEN_BUDGET = 16000;
    private static final ZonedDateTime BASE = ZonedDateTime.parse("2026-11-06T12:00:00Z");

    private final UUID chatId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final List<ChatMessage> rows = new ArrayList<>();

    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final ChatRepository chatRepository = mock(ChatRepository.class);
    private final SharedSessionRepository sharedSessionRepository = mock(SharedSessionRepository.class);
    private final SessionParticipantRepository sessionParticipantRepository = mock(SessionParticipantRepository.class);
    private final GeneratedImageService generatedImageService = mock(GeneratedImageService.class);

    private SessionService sessionService;
    private ConversationSessionService conversationSessionService;

    @BeforeEach
    void setUp() {
        sessionService = spy(DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .build());
        conversationSessionService = service(TOKEN_BUDGET);

        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setUserId(ownerId);
        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(sharedSessionRepository.findByChatIdAndMode(chatId, SessionMode.CONVERSATION)).thenReturn(Optional.empty());

        when(chatMessageRepository.findUnlinked(eq(chatId), any(), any(), any())).thenAnswer(invocation -> {
            Collection<MessageType> messageTypes = invocation.getArgument(1);
            ZonedDateTime before = invocation.getArgument(2);
            UUID inFlightId = invocation.getArgument(3);

            return rows.stream()
                    .filter(row -> row.getSessionEventId() == null)
                    .filter(row -> messageTypes.contains(row.getMessageType()))
                    .filter(row -> row.getTimestamp().isBefore(before))
                    .filter(row -> !row.getId().equals(inFlightId))
                    .sorted(Comparator.comparing(ChatMessage::getTimestamp))
                    .toList();
        });

        when(chatMessageRepository.linkSessionEvent(eq(chatId), any(), any())).thenAnswer(invocation -> {
            Collection<UUID> chatMessageIds = invocation.getArgument(1);
            String sessionEventId = invocation.getArgument(2);
            int linked = 0;

            for (ChatMessage row : rows) {
                if (chatMessageIds.contains(row.getId()) && row.getSessionEventId() == null) {
                    row.setSessionEventId(sessionEventId);
                    linked++;
                }
            }

            return linked;
        });

        when(chatMessageService.historyEntries(eq(chatId), anyList(), any())).thenAnswer(invocation -> {
            List<ChatMessage> chatMessages = invocation.getArgument(1);
            Map<UUID, String> participantNames = invocation.getArgument(2);

            return chatMessages.stream()
                    .map(row -> new HistoryEntry(row, row.getMessageType() == MessageType.USER
                            ? new UserMessage(participantNames == null ? row.getMessage() : participantNames.get(row.getSenderUserId()) + ": " + row.getMessage())
                            : new AssistantMessage(row.getMessage())))
                    .toList();
        });
    }

    private ConversationSessionService service(int tokenBudget) {
        ConversationAdvisors conversationAdvisors = new SessionMemoryConfig()
                .conversationAdvisors(sessionService, TRIGGER_TURNS, KEEP_TURNS, tokenBudget);

        return new ConversationSessionService(sessionService, chatMessageService, chatMessageRepository, chatRepository,
                sharedSessionRepository, sessionParticipantRepository, generatedImageService, conversationAdvisors,
                mock(PlatformTransactionManager.class), KEEP_TURNS);
    }

    private ChatMessage row(MessageType messageType, String text, int minute) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setId(UUID.randomUUID());
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(messageType);
        chatMessage.setMessage(text);
        chatMessage.setTimestamp(BASE.plusMinutes(minute));
        rows.add(chatMessage);

        return chatMessage;
    }

    private void createSession() {
        sessionService.create(CreateSessionRequest.builder().id(chatId.toString()).build());
    }

    private List<String> sessionTexts() {
        return sessionService.getMessages(chatId.toString()).stream().map(Message::getText).toList();
    }

    private List<String> viewTexts() {
        return sessionService.getActiveMessages(chatId.toString()).stream().map(Message::getText).toList();
    }

    @Test
    void aChatsFirstTurnCreatesItsSessionOwnedByTheChatOwner() {
        ChatMessage inFlight = row(MessageType.USER, "hello", 1);

        ConversationTurn conversationTurn = conversationSessionService.prepareTurn(chatId, inFlight, null);

        Session session = sessionService.findById(chatId.toString());
        assertThat(session).isNotNull();
        assertThat(session.metadata()).containsEntry(SessionAttributes.PRINCIPAL_NAME, ownerId.toString());
        assertThat(conversationTurn.sessionId()).isEqualTo(chatId.toString());
        assertThat(sessionTexts()).isEmpty();
    }

    /**
     * A chat that predates its session is copied in on its next turn, in order, each row as the event
     * named after it; the in-flight row, which the advisor stores itself, and anything newer stay out.
     */
    @Test
    void anExistingChatIsCopiedInOnceInOrderAndEachRowIsLinked() {
        ChatMessage question = row(MessageType.USER, "first question", 1);
        ChatMessage answer = row(MessageType.ASSISTANT, "first answer", 2);
        ChatMessage inFlight = row(MessageType.USER, "second question", 3);
        ChatMessage otherTabs = row(MessageType.USER, "sent from another tab", 4);

        conversationSessionService.prepareTurn(chatId, inFlight, null);
        conversationSessionService.prepareTurn(chatId, inFlight, null);

        assertThat(sessionTexts()).containsExactly("first question", "first answer");
        assertThat(sessionService.getEvents(chatId.toString())).extracting(SessionEvent::getId)
                .containsExactly(SessionLinks.eventId(question.getId()), SessionLinks.eventId(answer.getId()));
        assertThat(question.getSessionEventId()).isEqualTo(SessionLinks.eventId(question.getId()));
        assertThat(answer.getSessionEventId()).isEqualTo(SessionLinks.eventId(answer.getId()));
        assertThat(inFlight.getSessionEventId()).isNull();
        assertThat(otherTabs.getSessionEventId()).isNull();
    }

    /**
     * What the advisors never see — an agent's exchange, a news item — is written after the session
     * already exists, and reaches it on the next model turn, after what it already holds.
     */
    @Test
    void rowsWrittenOutsideTheAdvisorsAreCopiedInOnTheNextTurn() {
        row(MessageType.USER, "first question", 1);
        row(MessageType.ASSISTANT, "first answer", 2);
        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "unrelated", 3), null);
        rows.getLast().setSessionEventId("advisor-stored");

        row(MessageType.USER, "ask the weather agent", 4);
        row(MessageType.ASSISTANT, "the agent says rain", 5);
        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "back to you", 6), null);

        assertThat(sessionTexts()).containsExactly("first question", "first answer", "ask the weather agent", "the agent says rain");
    }

    /**
     * A news chat opens with the agent's message: with fewer turns than the window, everything is
     * copied, a leading assistant row included.
     */
    @Test
    void aChatWithFewerTurnsThanTheWindowIsCopiedWhole() {
        row(MessageType.ASSISTANT, "today's news", 1);

        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "tell me more", 2), null);

        assertThat(sessionTexts()).containsExactly("today's news");
    }

    @Test
    void rowsOlderThanTheWindowAreLeftOutAndMarkedSo() {
        ChatMessage oldest = row(MessageType.USER, "turn one", 1);
        ChatMessage oldestAnswer = row(MessageType.ASSISTANT, "answer one", 2);
        row(MessageType.USER, "turn two", 3);
        row(MessageType.ASSISTANT, "answer two", 4);
        row(MessageType.USER, "turn three", 5);
        row(MessageType.ASSISTANT, "answer three", 6);

        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "turn four", 7), null);

        assertThat(sessionTexts()).containsExactly("turn two", "answer two", "turn three", "answer three");
        assertThat(oldest.getSessionEventId()).isEqualTo(SessionLinks.OUTSIDE_WINDOW);
        assertThat(oldestAnswer.getSessionEventId()).isEqualTo(SessionLinks.OUTSIDE_WINDOW);
    }

    @Test
    void systemRowsAreNeverCopied() {
        row(MessageType.USER, "question", 1);
        ChatMessage cancelled = row(MessageType.SYSTEM, "Chat canceled.", 2);

        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "again", 3), null);

        assertThat(sessionTexts()).containsExactly("question");
        assertThat(cancelled.getSessionEventId()).isNull();
    }

    /**
     * In a shared conversation every copied user message is named, so the model can tell who said
     * what.
     */
    @Test
    void aSharedConversationIsCopiedWithItsParticipantsNames() {
        UUID sessionId = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        SharedSession sharedSession = mock(SharedSession.class);
        SessionParticipant participant = mock(SessionParticipant.class);
        when(sharedSession.getId()).thenReturn(sessionId);
        when(participant.getUserId()).thenReturn(alex);
        when(participant.getName()).thenReturn("Alex");
        when(sharedSessionRepository.findByChatIdAndMode(chatId, SessionMode.CONVERSATION)).thenReturn(Optional.of(sharedSession));
        when(sessionParticipantRepository.findBySessionIdOrderBySeatAsc(sessionId)).thenReturn(List.of(participant));

        row(MessageType.USER, "hello room", 1).setSenderUserId(alex);

        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "and again", 2), null);

        assertThat(sessionTexts()).containsExactly("Alex: hello room");
    }

    /**
     * The live request carries the attachment block as a message of its own ahead of the user's words,
     * so the session must end with exactly that message for the two to overlap; a retried turn does not
     * add it twice.
     */
    @Test
    void theTurnsAttachmentContextIsAppendedOnceAsItsOwnEvent() {
        ChatMessage inFlight = row(MessageType.USER, "what is this?", 1);

        conversationSessionService.prepareTurn(chatId, inFlight, "The user attached 1 image(s).");
        conversationSessionService.prepareTurn(chatId, inFlight, "The user attached 1 image(s).");

        assertThat(sessionService.getEvents(chatId.toString()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getId()).isEqualTo(SessionLinks.contextEventId(inFlight.getId()));
                    assertThat(event.getMessage()).isInstanceOf(UserMessage.class);
                    assertThat(event.getMessage().getText()).isEqualTo("The user attached 1 image(s).");
                });
    }

    @Test
    void aStaleVersionIsRetriedOnce() {
        row(MessageType.USER, "question", 1);
        doReturn(false).doCallRealMethod().when(sessionService).appendEvents(anyString(), anyList(), anyLong());

        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "again", 2), null);

        assertThat(sessionTexts()).containsExactly("question");
    }

    /**
     * A turn must not fail over bookkeeping the next turn can redo: the rows stay unlinked and are
     * copied then.
     */
    @Test
    void aSessionThatKeepsChangingLeavesTheRowsForTheNextTurn() {
        ChatMessage question = row(MessageType.USER, "question", 1);
        doReturn(false).when(sessionService).appendEvents(anyString(), anyList(), anyLong());

        assertThatCode(() -> conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "again", 2), null))
                .doesNotThrowAnyException();

        assertThat(question.getSessionEventId()).isNull();
        assertThat(sessionTexts()).isEmpty();
    }

    /**
     * The view grows to the trigger and is then cut back to the window, on a turn boundary, before the
     * turn's model call; every event stays in the log.
     */
    @Test
    void theViewIsBoundedToTheWindowOnceItHoldsMoreThanTheTrigger() {
        createSession();

        for (int turn = 1; turn <= TRIGGER_TURNS + 1; turn++) {
            sessionService.appendMessage(chatId.toString(), new UserMessage("question " + turn));
            sessionService.appendMessage(chatId.toString(), new AssistantMessage("answer " + turn));
        }

        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "next", 1), null);

        assertThat(viewTexts()).containsExactly("question 4", "answer 4", "question 5", "answer 5");
        assertThat(sessionTexts()).hasSize(2 * (TRIGGER_TURNS + 1));
    }

    @Test
    void aViewWithinTheTriggerIsLeftAlone() {
        createSession();

        for (int turn = 1; turn <= TRIGGER_TURNS; turn++) {
            sessionService.appendMessage(chatId.toString(), new UserMessage("question " + turn));
            sessionService.appendMessage(chatId.toString(), new AssistantMessage("answer " + turn));
        }

        conversationSessionService.prepareTurn(chatId, row(MessageType.USER, "next", 1), null);

        assertThat(viewTexts()).hasSize(2 * TRIGGER_TURNS);
    }

    /**
     * Within the turn window but over the token ceiling, the view is cut back on a turn boundary until it
     * fits, always keeping the newest turn.
     */
    @Test
    void theTokenCeilingCutsTheViewUntilItFitsKeepingTheNewestTurn() {
        ConversationSessionService ceilinged = service(60);
        createSession();

        String longText = "word ".repeat(80);

        sessionService.appendMessage(chatId.toString(), new UserMessage("older " + longText));
        sessionService.appendMessage(chatId.toString(), new AssistantMessage("older answer"));
        sessionService.appendMessage(chatId.toString(), new UserMessage("newest " + longText));
        sessionService.appendMessage(chatId.toString(), new AssistantMessage("newest answer"));

        ceilinged.prepareTurn(chatId, row(MessageType.USER, "next", 1), null);

        assertThat(viewTexts()).containsExactly("newest " + longText, "newest answer");
    }

    @Test
    void completeTurnLinksTheUserRowAndSavesTheReplyAsStoredByTheAdvisor() {
        UUID inFlightId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        sessionTurnRecord.recordUserEventId("event-1");
        ChatMessage reply = new ChatMessage();
        reply.setId(replyId);
        when(chatMessageService.saveAssistantReply(chatId, "the answer", SessionLinks.ADVISOR_REPLY)).thenReturn(Optional.of(reply));

        conversationSessionService.completeTurn(chatId, inFlightId, sessionTurnRecord, "the answer");

        verify(chatMessageRepository).linkSessionEvent(chatId, List.of(inFlightId), "event-1");
        verify(generatedImageService).bind(chatId, replyId);
    }

    /**
     * A {@code returnDirect} tool ended the loop with no model round, so the session holds a tool result
     * and no assistant message: the reply is saved unlinked and the next turn copies it.
     */
    @Test
    void completeTurnSavesAReplyNoModelRoundProducedUnlinked() {
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        sessionTurnRecord.recordFinishReason(ToolExecutionResult.FINISH_REASON);
        when(chatMessageService.saveAssistantReply(eq(chatId), eq("invoice created"), isNull())).thenReturn(Optional.empty());

        conversationSessionService.completeTurn(chatId, UUID.randomUUID(), sessionTurnRecord, "invoice created");

        verify(chatMessageService).saveAssistantReply(chatId, "invoice created", null);
        verify(chatMessageRepository, never()).linkSessionEvent(any(), any(), any());
    }

    @Test
    void completeTurnBindsNoImagesWhenNoReplyWasSaved() {
        when(chatMessageService.saveAssistantReply(chatId, "  ", SessionLinks.ADVISOR_REPLY)).thenReturn(Optional.empty());

        conversationSessionService.completeTurn(chatId, UUID.randomUUID(), new SessionTurnRecord(), "  ");

        verify(generatedImageService, never()).bind(any(), any());
    }

    /**
     * The reply is already on screen; losing an image's place in history is worth a log line, not the
     * turn.
     */
    @Test
    void completeTurnSurvivesAFailedImageBind() {
        ChatMessage reply = new ChatMessage();
        reply.setId(UUID.randomUUID());
        when(chatMessageService.saveAssistantReply(chatId, "here it is", SessionLinks.ADVISOR_REPLY)).thenReturn(Optional.of(reply));
        doThrow(new IllegalStateException("the database is down")).when(generatedImageService).bind(chatId, reply.getId());

        assertThatCode(() -> conversationSessionService.completeTurn(chatId, UUID.randomUUID(), new SessionTurnRecord(), "here it is"))
                .doesNotThrowAnyException();
    }

    @Test
    void abandonTurnLinksTheUserRowTheAdvisorStoredAndSavesNothing() {
        UUID inFlightId = UUID.randomUUID();
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        sessionTurnRecord.recordUserEventId("event-1");

        conversationSessionService.abandonTurn(chatId, inFlightId, sessionTurnRecord);

        verify(chatMessageRepository).linkSessionEvent(chatId, List.of(inFlightId), "event-1");
        verify(chatMessageService, never()).saveAssistantReply(any(), any(), any());
    }

    @Test
    void abandonTurnLeavesAUserRowTheAdvisorNeverStoredForTheNextTurn() {
        conversationSessionService.abandonTurn(chatId, UUID.randomUUID(), new SessionTurnRecord());

        verify(chatMessageRepository, never()).linkSessionEvent(any(), any(), any());
    }

    /**
     * The user watched the reply stream: a failed link must not also cost it its row.
     */
    @Test
    void completeTurnSavesTheReplyEvenWhenTheLinkFails() {
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        sessionTurnRecord.recordUserEventId("event-1");
        doThrow(new IllegalStateException("the database is down"))
                .when(chatMessageRepository).linkSessionEvent(any(), any(), any());
        when(chatMessageService.saveAssistantReply(chatId, "the answer", SessionLinks.ADVISOR_REPLY)).thenReturn(Optional.empty());

        assertThatCode(() -> conversationSessionService.completeTurn(chatId, UUID.randomUUID(), sessionTurnRecord, "the answer"))
                .doesNotThrowAnyException();

        verify(chatMessageService).saveAssistantReply(chatId, "the answer", SessionLinks.ADVISOR_REPLY);
    }

    @Test
    void abandonTurnNeverThrows() {
        SessionTurnRecord sessionTurnRecord = new SessionTurnRecord();
        sessionTurnRecord.recordUserEventId("event-1");
        doThrow(new IllegalStateException("the database is down"))
                .when(chatMessageRepository).linkSessionEvent(any(), any(), any());

        assertThatCode(() -> conversationSessionService.abandonTurn(chatId, UUID.randomUUID(), sessionTurnRecord))
                .doesNotThrowAnyException();
    }
}
