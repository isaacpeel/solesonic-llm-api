package com.solesonic.service.session;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.a2a.A2AParticipantMessage;
import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionMessageAccepted;
import com.solesonic.model.session.SessionMessageRequest;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SessionPrompt;
import com.solesonic.model.session.SessionPromptAnswer;
import com.solesonic.model.session.SessionTurn;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.service.a2a.A2AAgentService;
import com.solesonic.service.chat.ChatModelService;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.prompt.PromptService;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.redis.RedisStreamingChatService;
import com.solesonic.service.redis.StartedRun;
import com.solesonic.service.redis.TurnHooks;
import com.solesonic.service.redis.TurnOutcome;
import com.solesonic.service.redis.TurnPlan;
import com.solesonic.util.ElicitationLabels;
import com.solesonic.util.HiddenText;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Sends a message in a shared session: one short run that never parks. In a conversation the run is the
 * chat model answering the sender, through {@code PromptService.streamShared}; the rest of this applies
 * to an agent session. Whichever participant the
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

    /**
     * What the private chat's composer allows, so a shared one carries no more than it.
     */
    static final int MAX_ATTACHMENTS_PER_MESSAGE = 4;

    private final SharedSessionService sharedSessionService;
    private final SessionRunLock sessionRunLock;
    private final RedisStreamingChatService redisStreamingChatService;
    private final RedisStreamService redisStreamService;
    private final A2AAgentService a2aAgentService;
    private final A2AAgentRegistry a2aAgentRegistry;
    private final PromptService promptService;
    private final UserLlmKeyService userLlmKeyService;
    private final PushNotificationService pushNotificationService;
    private final SharedSessionSettings sharedSessionSettings;
    private final ChatAttachmentService chatAttachmentService;
    private final SharedCommandPolicy sharedCommandPolicy;
    private final ChatModelService chatModelService;

    public SessionMessageService(SharedSessionService sharedSessionService,
                                 SessionRunLock sessionRunLock,
                                 RedisStreamingChatService redisStreamingChatService,
                                 RedisStreamService redisStreamService,
                                 A2AAgentService a2aAgentService,
                                 A2AAgentRegistry a2aAgentRegistry,
                                 PromptService promptService,
                                 UserLlmKeyService userLlmKeyService,
                                 PushNotificationService pushNotificationService,
                                 SharedSessionSettings sharedSessionSettings,
                                 ChatAttachmentService chatAttachmentService,
                                 SharedCommandPolicy sharedCommandPolicy,
                                 ChatModelService chatModelService) {
        this.chatModelService = chatModelService;
        this.sharedSessionService = sharedSessionService;
        this.sessionRunLock = sessionRunLock;
        this.redisStreamingChatService = redisStreamingChatService;
        this.redisStreamService = redisStreamService;
        this.a2aAgentService = a2aAgentService;
        this.a2aAgentRegistry = a2aAgentRegistry;
        this.promptService = promptService;
        this.userLlmKeyService = userLlmKeyService;
        this.pushNotificationService = pushNotificationService;
        this.sharedSessionSettings = sharedSessionSettings;
        this.chatAttachmentService = chatAttachmentService;
        this.sharedCommandPolicy = sharedCommandPolicy;
    }

    /**
     * What a message sends: the text the transcript shows, and what the agent is sent. A conversation
     * message also carries the attachments and slash commands it runs with.
     */
    private record Outgoing(String displayText, String text, Map<String, Object> answerContent,
                            Set<UUID> attachmentIds, Set<String> commands) {

        Outgoing(String displayText, String text, Map<String, Object> answerContent) {
            this(displayText, text, answerContent, Set.of(), Set.of());
        }

        A2AParticipantMessage message(String senderName, List<String> participants) {
            return answerContent == null
                    ? A2AParticipantMessage.typed(text, senderName, participants)
                    : A2AParticipantMessage.answer(answerContent, senderName, participants);
        }
    }

    /**
     * @param authentication the sender's, which a conversation run needs for the tools it calls as
     *                       them; an agent run does not use it
     */
    public SessionMessageAccepted send(UUID sessionId, UUID senderUserId, SessionMessageRequest sessionMessageRequest,
                                       Authentication authentication) {
        SharedSession sharedSession = sharedSessionService.requireSeated(sessionId, senderUserId);

        requireAcceptsMessages(sharedSession);

        Outgoing outgoing = sharedSession.getMode() == SessionMode.CONVERSATION
                ? conversationOutgoing(senderUserId, sessionMessageRequest)
                : outgoing(sharedSession, senderUserId, sessionMessageRequest);

        return run(sharedSession, senderUserId, outgoing, false, authentication);
    }

    /**
     * The message an accepted invite opens the session with, sent as the participant who accepted it,
     * so the session starts where the inviter meant it to.
     */
    public SessionMessageAccepted openingMessage(SharedSession sharedSession, UUID senderUserId, String openingMessage) {
        return run(sharedSession, senderUserId, new Outgoing(openingMessage, openingMessage, null), true, null);
    }

    /**
     * A conversation message is text, any of the sender's own staged attachments, and any slash commands
     * the room may run (see {@link SharedCommandPolicy}); the text may be empty if it has one of the
     * others. It is held to the conversation's own length limit.
     */
    private Outgoing conversationOutgoing(UUID senderUserId, SessionMessageRequest sessionMessageRequest) {
        if (sessionMessageRequest == null || sessionMessageRequest.promptAnswer() != null) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "A message in this chat is text.");
        }

        String text = sessionMessageRequest.text() == null
                ? ""
                : HiddenText.strip(sessionMessageRequest.text().strip()).strip();

        Set<UUID> attachmentIds = attachmentIds(senderUserId, sessionMessageRequest.attachmentIds());

        //Ahead of the empty check: a refused command should say so, not that the message is empty.
        Set<String> commands = sharedCommandPolicy.require(sessionMessageRequest.commands());

        if (commands.stream().anyMatch(SlashCommand.SETTING_COMMANDS::contains)) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE,
                    "Settings such as the model are changed from the chat's own controls, not sent as a message.");
        }

        if (text.isEmpty() && attachmentIds.isEmpty() && commands.isEmpty()) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "A message cannot be empty.");
        }

        int limit = sharedSessionSettings.conversation().maxMessageCharacters();

        if (text.codePointCount(0, text.length()) > limit) {
            throw new SessionException(SessionErrorCode.MESSAGE_TOO_LONG, "Messages are limited to " + limit + " characters.");
        }

        return new Outgoing(text, text, null, attachmentIds, commands);
    }

    /**
     * The sender's own staged attachments, and nobody else's: an upload belongs to whoever staged it, and
     * one already in a chat is spoken for. The bind at save time is the authoritative check; this
     * refuses early, before a run is started.
     */
    private Set<UUID> attachmentIds(UUID senderUserId, Set<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of();
        }

        if (requested.size() > MAX_ATTACHMENTS_PER_MESSAGE || requested.stream().anyMatch(Objects::isNull)) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE,
                    "A message can carry up to " + MAX_ATTACHMENTS_PER_MESSAGE + " attachments.");
        }

        Set<UUID> attachmentIds = Set.copyOf(requested);

        if (!chatAttachmentService.allStagedFor(senderUserId, attachmentIds)) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE,
                    "Those attachments cannot be sent. Upload them again and retry.");
        }

        return attachmentIds;
    }

    private Outgoing outgoing(SharedSession sharedSession, UUID senderUserId, SessionMessageRequest sessionMessageRequest) {
        if (sessionMessageRequest == null || (sessionMessageRequest.text() == null) == (sessionMessageRequest.promptAnswer() == null)) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "A message is either text or an answer to the session's question.");
        }

        if (CollectionUtils.isNotEmpty(sessionMessageRequest.attachmentIds()) || CollectionUtils.isNotEmpty(sessionMessageRequest.commands())) {
            throw new SessionException(SessionErrorCode.INVALID_MESSAGE, "This session takes neither attachments nor commands.");
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
    private SessionMessageAccepted run(SharedSession sharedSession, UUID senderUserId, Outgoing outgoing, boolean opening,
                                       Authentication authentication) {
        userLlmKeyService.requireKey(senderUserId);

        boolean conversation = sharedSession.getMode() == SessionMode.CONVERSATION;

        if (conversation) {
            // Anyone seated may choose the chat's model, but each message runs on its sender's own key. Refuse
            // here, before a run exists, rather than letting the provider fail halfway through one.
            chatModelService.unusableSelectedModel(senderUserId, sharedSession.getChatId()).ifPresent(model -> {
                throw new SessionException(SessionErrorCode.MODEL_NOT_AVAILABLE,
                        "This chat is set to " + model + ", which your account can't use. Pick another model for the chat and send again.");
            });
        }

        if (!conversation && a2aAgentRegistry.agent(sharedSession.getAgentName()).isEmpty()) {
            throw new SessionException(SessionErrorCode.AGENT_UNAVAILABLE, "The agent is not available right now. Try again later.");
        }

        Duration runLock = sharedSessionSettings.runLock(sharedSession.getMode());

        List<SessionParticipant> participants = sharedSessionService.participants(sharedSession.getId());
        UUID streamUserId = SharedSessionService.streamOwner(participants);
        List<String> names = participants.stream().map(SessionParticipant::getName).toList();

        //The model is told who is in the room, not who was; someone who left still names their old messages.
        List<String> seatedNames = participants.stream()
                .filter(participant -> !participant.hasLeft())
                .map(SessionParticipant::getName)
                .toList();

        SessionParticipant sender = participants.stream()
                .filter(participant -> participant.getUserId().equals(senderUserId))
                .findFirst()
                .orElseThrow(SharedSessionService::sessionNotFound);

        UUID sessionId = sharedSession.getId();
        UUID chatId = sharedSession.getChatId();
        String agentName = sharedSession.getAgentName();
        UUID runId = UUID.randomUUID();

        if (!sessionRunLock.acquire(sessionId, runId, runLock)) {
            throw new SessionException(SessionErrorCode.RUN_IN_PROGRESS, "The agent is still answering the last message.");
        }

        try {
            String cursor = redisStreamService.getLatestOffset(chatId, streamUserId).block(LOOKUP_TIMEOUT);

            if (cursor != null) {
                sessionRunLock.recordCursor(sessionId, cursor, runLock);
            }
        } catch (RuntimeException exception) {
            sessionRunLock.release(sessionId, runId);
            throw exception;
        }

        SessionTurnCollector collector = new SessionTurnCollector();
        ChatRequest chatRequest = new ChatRequest(outgoing.displayText(), outgoing.commands(), outgoing.attachmentIds(), null);
        String senderName = sender.getName();

        Supplier<Flux<String>> chunks;

        if (conversation) {
            chunks = () -> promptService.streamShared(chatId, senderUserId, senderName, seatedNames, chatRequest, authentication);
        } else {
            A2AParticipantMessage participantMessage = outgoing.message(senderName, names);

            chunks = () -> a2aAgentService.delegateShared(chatId, agentName, participantMessage, collector);
        }

        TurnPlan turnPlan = new TurnPlan(runId,
                chatId,
                streamUserId,
                senderUserId,
                senderName,
                chatRequest,
                chunks,
                new SessionTurnHooks(sessionId, chatId, streamUserId, senderUserId, opening, conversation, collector));

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
        private final boolean conversation;
        private final SessionTurnCollector collector;
        private final AtomicReference<SharedSessionSummary> applied = new AtomicReference<>();

        private SessionTurnHooks(UUID sessionId,
                                 UUID chatId,
                                 UUID streamUserId,
                                 UUID senderUserId,
                                 boolean opening,
                                 boolean conversation,
                                 SessionTurnCollector collector) {
            this.sessionId = sessionId;
            this.chatId = chatId;
            this.streamUserId = streamUserId;
            this.senderUserId = senderUserId;
            this.opening = opening;
            this.conversation = conversation;
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

            if (conversation) {
                if (outcome == TurnOutcome.COMPLETED) {
                    pushSharedChatMessage(runId);
                }

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

        /**
         * Everyone still seated except the sender, read now: people join and leave while a run goes on.
         * The push service applies the {@code session_turns} preference, presence and the throttle.
         */
        private void pushSharedChatMessage(UUID runId) {
            for (SessionParticipant participant : sharedSessionService.participants(sessionId)) {
                if (participant.hasLeft() || participant.getUserId().equals(senderUserId)) {
                    continue;
                }

                pushNotificationService.push(participant.getUserId(), new PushEvent.SharedChatMessage(chatId, sessionId, runId));
            }
        }

        @Override
        public void finished(UUID runId) {
            sessionRunLock.release(sessionId, runId);
        }
    }
}
