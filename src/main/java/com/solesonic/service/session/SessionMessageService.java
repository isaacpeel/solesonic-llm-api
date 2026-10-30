package com.solesonic.service.session;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.a2a.A2AParticipantMessage;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionMessageAccepted;
import com.solesonic.model.session.SessionMessageRequest;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SessionPrompt;
import com.solesonic.model.session.SessionPromptAnswer;
import com.solesonic.model.session.SessionTurn;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.redis.RedisStreamingChatService;
import com.solesonic.service.redis.StartedRun;
import com.solesonic.service.redis.TurnHooks;
import com.solesonic.service.redis.TurnOutcome;
import com.solesonic.service.redis.TurnPlan;
import com.solesonic.util.AuthenticationTokens;
import com.solesonic.util.ElicitationLabels;
import com.solesonic.util.HiddenText;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends a message in a shared session: one short run that never parks. Whichever participant the
 * session waits on, the message reaches the agent with its sender and the participants, so the agent,
 * not the text, decides who may act. A typed message is never checked against the turn here, because
 * an agent may accept some messages from anyone; an answer to the session's open question is, since
 * it stands for a choice only the participant it was asked of can make.
 * <p>
 * One run at a time per session ({@link SessionRunLock}): every participant reads the one stream, and
 * a second run on it would interleave the two replies.
 */
@Service
public class SessionMessageService {
    private static final Logger log = LoggerFactory.getLogger(SessionMessageService.class);

    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final String ACCEPT = "accept";
    private static final String PROPERTIES = "properties";

    private final SharedSessionService sharedSessionService;
    private final SessionRunLock sessionRunLock;
    private final RedisStreamingChatService redisStreamingChatService;
    private final RedisStreamService redisStreamService;
    private final A2AAgentService a2aAgentService;
    private final A2AAgentRegistry a2aAgentRegistry;
    private final UserLlmKeyService userLlmKeyService;
    private final PushNotificationService pushNotificationService;
    private final SharedSessionSettings sharedSessionSettings;

    public SessionMessageService(SharedSessionService sharedSessionService,
                                 SessionRunLock sessionRunLock,
                                 RedisStreamingChatService redisStreamingChatService,
                                 RedisStreamService redisStreamService,
                                 A2AAgentService a2aAgentService,
                                 A2AAgentRegistry a2aAgentRegistry,
                                 UserLlmKeyService userLlmKeyService,
                                 PushNotificationService pushNotificationService,
                                 SharedSessionSettings sharedSessionSettings) {
        this.sharedSessionService = sharedSessionService;
        this.sessionRunLock = sessionRunLock;
        this.redisStreamingChatService = redisStreamingChatService;
        this.redisStreamService = redisStreamService;
        this.a2aAgentService = a2aAgentService;
        this.a2aAgentRegistry = a2aAgentRegistry;
        this.userLlmKeyService = userLlmKeyService;
        this.pushNotificationService = pushNotificationService;
        this.sharedSessionSettings = sharedSessionSettings;
    }

    /**
     * What a message sends: the text the transcript shows, and what the agent is sent.
     */
    private record Outgoing(String displayText, String text, Map<String, Object> answerContent) {

        A2AParticipantMessage message(String senderName, List<String> participants) {
            return answerContent == null
                    ? A2AParticipantMessage.typed(text, senderName, participants)
                    : A2AParticipantMessage.answer(answerContent, senderName, participants);
        }
    }

    public SessionMessageAccepted send(UUID sessionId, UUID senderUserId, SessionMessageRequest sessionMessageRequest, Authentication authentication) {
        SharedSession sharedSession = sharedSessionService.requireSeated(sessionId, senderUserId);

        requireAcceptsMessages(sharedSession);

        Outgoing outgoing = outgoing(sharedSession, senderUserId, sessionMessageRequest);

        return run(sharedSession, senderUserId, outgoing, authentication, false);
    }

    /**
     * The message an accepted invite opens the session with, sent as the participant who accepted it,
     * so the session starts where the inviter meant it to.
     */
    public SessionMessageAccepted openingMessage(SharedSession sharedSession, UUID senderUserId, String openingMessage, Authentication authentication) {
        return run(sharedSession, senderUserId, new Outgoing(openingMessage, openingMessage, null), authentication, true);
    }

    private Outgoing outgoing(SharedSession sharedSession, UUID senderUserId, SessionMessageRequest sessionMessageRequest) {
        if (sessionMessageRequest == null || (sessionMessageRequest.text() == null) == (sessionMessageRequest.promptAnswer() == null)) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "A message is either text or an answer to the session's question.");
        }

        if (sessionMessageRequest.text() != null) {
            String text = sessionMessageRequest.text().strip();

            if (text.isEmpty()) {
                throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "A message cannot be empty.");
            }

            requireLength(text);

            String displayText = HiddenText.strip(text).strip();

            if (displayText.isEmpty()) {
                throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "A message cannot be empty.");
            }

            SessionTurn turn = sharedSession.getTurnState();

            if (turn != null && !turn.inputHiddenFrom().isEmpty() && turn.userIds().contains(senderUserId)) {
                return new Outgoing(HiddenText.wrap(turn.inputHiddenFrom(), displayText), text, null);
            }

            return new Outgoing(displayText, text, null);
        }

        return answer(sharedSession.getPendingPrompt(), senderUserId, sessionMessageRequest.promptAnswer());
    }

    /**
     * Only the fields the question asked for are sent on, as with every elicitation, and the transcript
     * shows the option the participant picked rather than its value.
     */
    private Outgoing answer(SessionPrompt pendingPrompt, UUID senderUserId, SessionPromptAnswer promptAnswer) {
        if (pendingPrompt == null || !pendingPrompt.elicitationId().equals(promptAnswer.elicitationId())) {
            throw new SessionException(SessionErrorCode.NOT_YOUR_TURN, "That question has already been answered.");
        }

        if (!pendingPrompt.forUserIds().contains(senderUserId)) {
            throw new SessionException(SessionErrorCode.NOT_YOUR_TURN, "That question is for another participant.");
        }

        if (!ACCEPT.equalsIgnoreCase(promptAnswer.action()) || promptAnswer.content() == null) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "Pick an option, or type your message instead.");
        }

        Map<String, Object> properties = properties(pendingPrompt.requestedSchema());
        Map<String, Object> narrowed = new LinkedHashMap<>();

        for (String propertyName : properties.keySet()) {
            if (promptAnswer.content().containsKey(propertyName)) {
                narrowed.put(propertyName, promptAnswer.content().get(propertyName));
            }
        }

        String summary = ElicitationLabels.summarize(properties, narrowed);

        if (StringUtils.isBlank(summary)) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "Pick an option, or type your message instead.");
        }

        requireLength(summary);

        return new Outgoing(summary, null, narrowed);
    }

    private static Map<String, Object> properties(Map<String, Object> requestedSchema) {
        Map<String, Object> properties = new LinkedHashMap<>();

        if (requestedSchema != null && requestedSchema.get(PROPERTIES) instanceof Map<?, ?> schemaProperties) {
            schemaProperties.forEach((name, schema) -> properties.put(String.valueOf(name), schema));
        }

        return properties;
    }

    private void requireLength(String text) {
        if (text.codePointCount(0, text.length()) > sharedSessionSettings.maxMessageCharacters()) {
            throw new SessionException(SessionErrorCode.MESSAGE_TOO_LONG,
                    "Messages are limited to " + sharedSessionSettings.maxMessageCharacters() + " characters.");
        }
    }

    private static void requireAcceptsMessages(SharedSession sharedSession) {
        if (!sharedSession.getStatus().acceptsMessages()) {
            throw new SessionException(SessionErrorCode.SESSION_CLOSED, "This session is over.");
        }
    }

    /**
     * Answers once the run's {@code RUN_STARTED} frame is out; the run itself goes on without the
     * caller.
     */
    private SessionMessageAccepted run(SharedSession sharedSession, UUID senderUserId, Outgoing outgoing, Authentication authentication, boolean opening) {
        userLlmKeyService.requireKey(senderUserId);

        if (a2aAgentRegistry.agent(sharedSession.getAgentName()).isEmpty()) {
            throw new SessionException(SessionErrorCode.AGENT_UNAVAILABLE, "The agent is not available right now. Try again later.");
        }

        List<SessionParticipant> participants = sharedSessionService.participants(sharedSession.getId());
        UUID streamUserId = SharedSessionService.streamOwner(participants);
        List<String> names = participants.stream().map(SessionParticipant::getName).toList();

        SessionParticipant sender = participants.stream()
                .filter(participant -> participant.getUserId().equals(senderUserId))
                .findFirst()
                .orElseThrow(SharedSessionService::sessionNotFound);

        String userToken = AuthenticationTokens.token(authentication);
        UUID sessionId = sharedSession.getId();
        UUID chatId = sharedSession.getChatId();
        String agentName = sharedSession.getAgentName();
        UUID runId = UUID.randomUUID();

        if (!sessionRunLock.acquire(sessionId, runId, sharedSessionSettings.runLock())) {
            throw new SessionException(SessionErrorCode.RUN_IN_PROGRESS, "The agent is still answering the last message.");
        }

        try {
            String cursor = redisStreamService.getLatestOffset(chatId, streamUserId).block(LOOKUP_TIMEOUT);

            if (cursor != null) {
                sessionRunLock.recordCursor(sessionId, cursor, sharedSessionSettings.runLock());
            }
        } catch (RuntimeException exception) {
            sessionRunLock.release(sessionId, runId);
            throw exception;
        }

        SessionTurnCollector collector = new SessionTurnCollector();
        A2AParticipantMessage participantMessage = outgoing.message(sender.getName(), names);

        TurnPlan turnPlan = new TurnPlan(runId,
                chatId,
                streamUserId,
                senderUserId,
                sender.getName(),
                new ChatRequest(outgoing.displayText(), Set.of(), Set.of(), null),
                () -> a2aAgentService.delegateShared(chatId, agentName, participantMessage, userToken, collector),
                new SessionTurnHooks(sessionId, chatId, streamUserId, senderUserId, opening, collector));

        log.info("Seat {} wrote in shared session {}: run {}{}", sender.getSeat(), sessionId, runId, opening ? " (the opening message)" : "");

        try {
            StartedRun startedRun = redisStreamingChatService.start(turnPlan).block(START_TIMEOUT);

            if (startedRun == null) {
                throw new IllegalStateException("The run of shared session " + sessionId + " did not start");
            }

            return new SessionMessageAccepted(startedRun.runId(), startedRun.messageId());
        } catch (RuntimeException exception) {
            sessionRunLock.release(sessionId, runId);
            throw exception;
        }
    }

    /**
     * Ahead of a run's terminal frame, the session takes what the agent reported and every participant
     * is sent it as a {@code session_state} frame; once the frame is out, whoever the session now waits
     * on is pushed. The lock goes when the run does, by any path.
     */
    private final class SessionTurnHooks implements TurnHooks {
        private final UUID sessionId;
        private final UUID chatId;
        private final UUID streamUserId;
        private final UUID senderUserId;
        private final boolean opening;
        private final SessionTurnCollector collector;
        private final AtomicReference<SharedSessionSummary> applied = new AtomicReference<>();

        private SessionTurnHooks(UUID sessionId,
                                 UUID chatId,
                                 UUID streamUserId,
                                 UUID senderUserId,
                                 boolean opening,
                                 SessionTurnCollector collector) {
            this.sessionId = sessionId;
            this.chatId = chatId;
            this.streamUserId = streamUserId;
            this.senderUserId = senderUserId;
            this.opening = opening;
            this.collector = collector;
        }

        @Override
        public Mono<Void> beforeTerminal(UUID runId, TurnOutcome outcome) {
            return Mono.fromCallable(() -> sharedSessionService.applyOutcome(sessionId, runId, outcome, collector))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(applied::set)
                    .flatMap(sharedSessionSummary -> sharedSessionService.publishState(chatId, streamUserId, sharedSessionSummary));
        }

        @Override
        public void afterTerminal(UUID runId, TurnOutcome outcome) {
            if (outcome == TurnOutcome.FAILED) {
                pushNotificationService.push(senderUserId, new PushEvent.TurnFailed(chatId, runId));
                return;
            }

            SharedSessionSummary sharedSessionSummary = applied.get();

            if (outcome != TurnOutcome.COMPLETED || sharedSessionSummary == null || sharedSessionSummary.turn() == null
                    || !sharedSessionSummary.status().acceptsMessages()) {
                return;
            }

            List<UUID> waitingOn = sharedSessionSummary.turn().userIds();

            for (UUID userId : waitingOn) {
                pushNotificationService.push(userId, new PushEvent.SessionTurn(chatId, sessionId, runId, userId));
            }

            if (opening && !waitingOn.contains(streamUserId)) {
                pushNotificationService.push(streamUserId, new PushEvent.SessionStarted(chatId, sessionId, runId, streamUserId));
            }
        }

        @Override
        public void finished(UUID runId) {
            sessionRunLock.release(sessionId, runId);
        }
    }
}
