package com.solesonic.service.chat;

import com.solesonic.config.chat.ConversationAdvisors;
import com.solesonic.config.chat.SessionCompaction;
import com.solesonic.model.chat.SessionTurnRecord;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.chat.history.HistoryEntry;
import com.solesonic.model.chat.history.SessionLinks;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.session.SessionParticipantRepository;
import com.solesonic.repository.session.SharedSessionRepository;
import com.solesonic.service.image.GeneratedImageService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everything this application does to a chat's model session. The session is the model's memory:
 * bounded, derived, read by nothing but the session advisors. {@code chat_message} is the user's
 * history and stays the only record the product reads.
 * <p>
 * The advisors store a live turn themselves. What they never see — the history of a chat that
 * predates its session, replies from A2A agents, interest-agent news, the user message of a turn that
 * failed before the model was called, rows forked into a new chat — is copied in here before each
 * turn, once each, and only as far back as the window. Data only ever flows {@code chat_message} to
 * session, never back.
 */
@Service
public class ConversationSessionService {
    private static final Logger log = LoggerFactory.getLogger(ConversationSessionService.class);

    private static final Set<MessageType> HISTORY_TYPES = Set.of(MessageType.USER, MessageType.ASSISTANT);
    private static final int APPEND_ATTEMPTS = 2;

    private final SessionService sessionService;
    private final ChatMessageService chatMessageService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRepository chatRepository;
    private final SharedSessionRepository sharedSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final GeneratedImageService generatedImageService;
    private final ConversationAdvisors conversationAdvisors;
    private final TransactionTemplate transactionTemplate;
    private final int compactionKeepTurns;

    public ConversationSessionService(SessionService sessionService,
                                      ChatMessageService chatMessageService,
                                      ChatMessageRepository chatMessageRepository,
                                      ChatRepository chatRepository,
                                      SharedSessionRepository sharedSessionRepository,
                                      SessionParticipantRepository sessionParticipantRepository,
                                      GeneratedImageService generatedImageService,
                                      ConversationAdvisors conversationAdvisors,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${solesonic.llm.session.compaction-keep-turns}") int compactionKeepTurns) {
        this.sessionService = sessionService;
        this.chatMessageService = chatMessageService;
        this.chatMessageRepository = chatMessageRepository;
        this.chatRepository = chatRepository;
        this.sharedSessionRepository = sharedSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.generatedImageService = generatedImageService;
        this.conversationAdvisors = conversationAdvisors;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.compactionKeepTurns = compactionKeepTurns;
    }

    /**
     * Readies the chat's session for a turn the advisors are about to run: copies in what they never
     * stored, bounds the view by the turn window and then the token ceiling, and adds this turn's
     * attachment context. Called next to every {@code ChatClient} call that carries the session
     * advisors, before it, so every prompt is bounded.
     * <p>
     * The copy commits on its own, so the session row lock it takes is held only that long. Compaction
     * runs after it, outside any transaction, the way the turn advisor runs it.
     *
     * @param inFlightMessage   the turn's user row, already saved. The advisor stores the live message
     *                          itself, so this row and everything newer are never copied
     * @param attachmentContext the attachment block the request carries as a message of its own, or
     *                          null. Stored as its own event so the request {@code [context, user]}
     *                          overlaps the history by exactly that message, and the model sees it
     *                          once
     */
    public ConversationTurn prepareTurn(UUID chatId, ChatMessage inFlightMessage, String attachmentContext) {
        String sessionId = chatId.toString();

        transactionTemplate.executeWithoutResult(_ -> copyHistory(chatId, sessionId, inFlightMessage));

        compact(sessionId, conversationAdvisors.turnWindow());
        compact(sessionId, conversationAdvisors.tokenCeiling());

        if (StringUtils.isNotBlank(attachmentContext)) {
            sessionService.appendEvent(SessionEvent.builder()
                    .id(SessionLinks.contextEventId(inFlightMessage.getId()))
                    .sessionId(sessionId)
                    .message(new UserMessage(attachmentContext))
                    .build());
        }

        return new ConversationTurn(this, chatId, inFlightMessage.getId());
    }

    /**
     * The turn's stream completed, so the advisor has already stored the reply. Links the user row to
     * the event the advisor stored it as, then saves the reply as the turn's assistant row and claims
     * the images the turn generated. The only place an assistant row of an advisor turn is written.
     * <p>
     * A loop that ended without a model round — a {@code returnDirect} tool, a breached tool-call limit
     * — left a tool result and no assistant message in the session, so the row is saved unlinked and
     * the next turn copies it after that result.
     */
    public void completeTurn(UUID chatId, UUID inFlightMessageId, SessionTurnRecord sessionTurnRecord, String text) {
        linkUserMessageQuietly(chatId, inFlightMessageId, sessionTurnRecord);

        String sessionEventId = sessionTurnRecord.endedWithoutModelRound() ? null : SessionLinks.ADVISOR_REPLY;

        chatMessageService.saveAssistantReply(chatId, text, sessionEventId)
                .ifPresent(saved -> bindGeneratedImages(chatId, saved.getId()));
    }

    /**
     * The turn failed or was cancelled. Nothing is saved, as before: a turn that did not finish has no
     * assistant row. If the advisor stored the user message first, the row is linked to it; if not, it
     * stays unlinked and the next turn copies it. Never throws, because it runs while a turn is already
     * failing.
     */
    public void abandonTurn(UUID chatId, UUID inFlightMessageId, SessionTurnRecord sessionTurnRecord) {
        linkUserMessageQuietly(chatId, inFlightMessageId, sessionTurnRecord);
    }

    /**
     * A failed link must not cost the turn its reply. The row stays unlinked, so the next turn copies the
     * message the advisor already stored: a near-duplicate in the model's view, logged here.
     */
    private void linkUserMessageQuietly(UUID chatId, UUID inFlightMessageId, SessionTurnRecord sessionTurnRecord) {
        try {
            linkUserMessage(chatId, inFlightMessageId, sessionTurnRecord);
        } catch (RuntimeException runtimeException) {
            log.warn("Could not link the user message of a turn in chat {}; the next turn copies it again: {}",
                    chatId, runtimeException.getClass().getSimpleName());
        }
    }

    private void copyHistory(UUID chatId, String sessionId, ChatMessage inFlightMessage) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalStateException("Chat not found: " + chatId));

        sessionService.findOrCreate(CreateSessionRequest.builder()
                .id(sessionId)
                .metadata(SessionAttributes.PRINCIPAL_NAME, chat.getUserId().toString())
                .build());

        for (int attempt = 1; attempt <= APPEND_ATTEMPTS; attempt++) {
            long version = sessionService.getEventVersion(sessionId);

            List<ChatMessage> unlinked = chatMessageRepository.findUnlinked(chatId, HISTORY_TYPES,
                    inFlightMessage.getTimestamp(), inFlightMessage.getId());

            if (unlinked.isEmpty()) {
                return;
            }

            int windowStart = windowStart(unlinked);
            List<ChatMessage> outsideWindow = unlinked.subList(0, windowStart);
            List<HistoryEntry> historyEntries = chatMessageService.historyEntries(chatId,
                    unlinked.subList(windowStart, unlinked.size()), participantNames(chatId));

            List<SessionEvent> events = historyEntries.stream()
                    .map(historyEntry -> SessionEvent.builder()
                            .id(SessionLinks.eventId(historyEntry.chatMessage().getId()))
                            .sessionId(sessionId)
                            .timestamp(historyEntry.chatMessage().getTimestamp().toInstant())
                            .message(historyEntry.message())
                            .build())
                    .toList();

            if (!events.isEmpty() && !sessionService.appendEvents(sessionId, events, version)) {
                continue;
            }

            for (HistoryEntry historyEntry : historyEntries) {
                UUID chatMessageId = historyEntry.chatMessage().getId();
                chatMessageRepository.linkSessionEvent(chatId, List.of(chatMessageId), SessionLinks.eventId(chatMessageId));
            }

            if (!outsideWindow.isEmpty()) {
                chatMessageRepository.linkSessionEvent(chatId,
                        outsideWindow.stream().map(ChatMessage::getId).toList(), SessionLinks.OUTSIDE_WINDOW);
            }

            log.info("Copied {} message(s) of chat {} into its session, {} older than the window left out",
                    events.size(), chatId, outsideWindow.size());

            return;
        }

        log.warn("Could not copy the history of chat {} into its session: the session kept changing. "
                + "The next turn copies it", chatId);
    }

    /**
     * Where the window starts among the rows to copy: at the K-th newest user row, so that only the
     * last K turns are copied. Everything when there are fewer.
     */
    private int windowStart(List<ChatMessage> unlinked) {
        int userRows = 0;

        for (int index = unlinked.size() - 1; index >= 0; index--) {
            if (unlinked.get(index).getMessageType() == MessageType.USER && ++userRows == compactionKeepTurns) {
                return index;
            }
        }

        return 0;
    }

    /**
     * Who said what, for a shared conversation: every participant's name by user id, including people
     * who have left, because their messages are still in the history. Null for any other chat.
     */
    private Map<UUID, String> participantNames(UUID chatId) {
        return sharedSessionRepository.findByChatIdAndMode(chatId, SessionMode.CONVERSATION)
                .map(sharedSession -> sessionParticipantRepository.findBySessionIdOrderBySeatAsc(sharedSession.getId()).stream()
                        .collect(Collectors.toMap(SessionParticipant::getUserId, SessionParticipant::getName, (first, _) -> first)))
                .orElse(null);
    }

    private void linkUserMessage(UUID chatId, UUID inFlightMessageId, SessionTurnRecord sessionTurnRecord) {
        sessionTurnRecord.userEventId().ifPresent(eventId -> transactionTemplate.executeWithoutResult(_ ->
                chatMessageRepository.linkSessionEvent(chatId, List.of(inFlightMessageId), eventId)));
    }

    /**
     * Best-effort, as the turn advisor's own compaction is: a failure leaves the view unbounded for one
     * turn and is retried on the next.
     */
    private void compact(String sessionId, SessionCompaction sessionCompaction) {
        try {
            sessionService.compact(sessionId, sessionCompaction.trigger(), sessionCompaction.strategy());
        } catch (RuntimeException runtimeException) {
            log.warn("Could not compact session {}; the next turn retries: {}", sessionId,
                    runtimeException.getClass().getSimpleName());
        }
    }

    /**
     * Bookkeeping, and bookkeeping must not cost the user their answer: the reply is already on screen,
     * and an exception here would report the finished turn as failed. The image is stored either way;
     * what is lost is its place in history, which is worth a log line and nothing more.
     */
    private void bindGeneratedImages(UUID chatId, UUID chatMessageId) {
        try {
            generatedImageService.bind(chatId, chatMessageId);
        } catch (RuntimeException runtimeException) {
            log.error("Could not attach generated images to chat message {}", chatMessageId, runtimeException);
        }
    }
}
