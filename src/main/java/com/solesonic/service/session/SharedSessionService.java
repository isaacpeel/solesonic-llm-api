package com.solesonic.service.session;

import com.agui.community.core.event.CustomEvent;
import com.agui.community.core.event.EventType;
import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.a2a.A2AParticipantsExtension;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatKind;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionInvite;
import com.solesonic.model.session.SessionParticipant;
import com.solesonic.model.session.SessionParticipantSummary;
import com.solesonic.model.session.SessionPrompt;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SessionTurn;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.model.user.UserProfile;
import com.solesonic.redis.service.RedisStreamService;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.session.SessionParticipantRepository;
import com.solesonic.repository.session.SharedSessionRepository;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.chat.ChatMessageService;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.redis.TurnOutcome;
import com.solesonic.service.user.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A shared session's chat, participants and state: several users working with one A2A agent. The
 * inviter owns the session's chat, which is what its stream key is built from, and always sits in seat
 * 0, so the stream owner is the seat-0 participant.
 * <p>
 * The turn and the open question are what the agent reported at the end of the last run. They are
 * applied under a lock on the session row, and a run whose session was closed while it ran changes
 * nothing.
 */
@Service
public class SharedSessionService {
    private static final Logger log = LoggerFactory.getLogger(SharedSessionService.class);

    public static final String SESSION_STATE = "session_state";
    static final int STREAM_OWNER_SEAT = 0;

    private final SharedSessionRepository sharedSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final ChatRepository chatRepository;
    private final ChatService chatService;
    private final ChatMessageService chatMessageService;
    private final UserProfileService userProfileService;
    private final A2AStickyAgentService a2aStickyAgentService;
    private final RedisStreamService redisStreamService;
    private final SessionRunLock sessionRunLock;
    private final SharedSessionSettings sharedSessionSettings;
    private final Clock clock;

    public SharedSessionService(SharedSessionRepository sharedSessionRepository,
                                SessionParticipantRepository sessionParticipantRepository,
                                ChatRepository chatRepository,
                                ChatService chatService,
                                ChatMessageService chatMessageService,
                                UserProfileService userProfileService,
                                A2AStickyAgentService a2aStickyAgentService,
                                RedisStreamService redisStreamService,
                                SessionRunLock sessionRunLock,
                                SharedSessionSettings sharedSessionSettings,
                                Clock clock) {
        this.sharedSessionRepository = sharedSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.chatRepository = chatRepository;
        this.chatService = chatService;
        this.chatMessageService = chatMessageService;
        this.userProfileService = userProfileService;
        this.a2aStickyAgentService = a2aStickyAgentService;
        this.redisStreamService = redisStreamService;
        this.sessionRunLock = sessionRunLock;
        this.sharedSessionSettings = sharedSessionSettings;
        this.clock = clock;
    }

    /**
     * The session an accepted invite starts: its chat, owned by the inviter, and the seats. Called
     * inside the transaction that accepts the invite, so the two are written together or not at all.
     */
    @Transactional
    public SharedSession create(SessionInvite invite) {
        ZonedDateTime now = ZonedDateTime.now(clock);

        Chat chat = new Chat();
        chat.setUserId(invite.getInviterUserId());
        chat.setKind(ChatKind.SHARED);
        chat.setName(invite.getAgentName());
        chat.setTimestamp(now);
        chat = chatRepository.save(chat);

        SharedSession sharedSession = new SharedSession();
        sharedSession.setId(UUID.randomUUID());
        sharedSession.setChatId(chat.getId());
        sharedSession.setAgentName(invite.getAgentName());
        sharedSession.setStatus(SessionStatus.ACTIVE);
        sharedSession.setCreated(now);
        sharedSession.setUpdated(now);
        sharedSession = sharedSessionRepository.save(sharedSession);

        List<UUID> userIds = List.of(invite.getInviterUserId(), invite.getInviteeUserId());
        Map<UUID, UserProfile> profiles = userProfileService.profiles(userIds);

        List<String> names = ParticipantNames.assign(userIds.stream()
                .map(profiles::get)
                .map(profile -> profile == null
                        ? new ParticipantNames.Candidate(null, null)
                        : new ParticipantNames.Candidate(profile.displayName(), profile.username()))
                .toList(), sharedSessionSettings.maxNameCharacters());

        for (int seat = 0; seat < userIds.size(); seat++) {
            SessionParticipant participant = new SessionParticipant();
            participant.setId(UUID.randomUUID());
            participant.setSessionId(sharedSession.getId());
            participant.setUserId(userIds.get(seat));
            participant.setSeat(seat);
            participant.setName(names.get(seat));
            participant.setJoined(now);
            sessionParticipantRepository.save(participant);
        }

        log.info("Created shared session {} in chat {} with agent {}", sharedSession.getId(), chat.getId(), sharedSession.getAgentName());

        return sharedSession;
    }

    /**
     * @throws SessionException a {@code 404} when the caller does not take part, and never did
     */
    @Transactional(readOnly = true)
    public SharedSession requireParticipant(UUID sessionId, UUID userId) {
        return sharedSessionRepository.findForParticipant(sessionId, userId)
                .orElseThrow(SharedSessionService::sessionNotFound);
    }

    /**
     * {@link #requireParticipant} for a caller who has not left: only a seated participant may write.
     */
    @Transactional(readOnly = true)
    public SharedSession requireSeated(UUID sessionId, UUID userId) {
        return sharedSessionRepository.findForSeatedParticipant(sessionId, userId)
                .orElseThrow(SharedSessionService::sessionNotFound);
    }

    @Transactional(readOnly = true)
    public List<SessionParticipant> participants(UUID sessionId) {
        return sessionParticipantRepository.findBySessionIdOrderBySeatAsc(sessionId);
    }

    public static UUID streamOwner(List<SessionParticipant> participants) {
        return participants.stream()
                .filter(participant -> participant.getSeat() == STREAM_OWNER_SEAT)
                .map(SessionParticipant::getUserId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A shared session has no seat " + STREAM_OWNER_SEAT));
    }

    @Transactional(readOnly = true)
    public SharedSessionSummary summary(UUID sessionId, UUID viewerUserId) {
        SharedSession sharedSession = requireParticipant(sessionId, viewerUserId);

        return summary(sharedSession, participants(sessionId), viewerUserId, sessionRunLock.isHeld(sessionId));
    }

    @Transactional(readOnly = true)
    public Page<SharedSessionSummary> list(UUID viewerUserId, Collection<SessionStatus> statuses, Pageable pageable) {
        Page<SharedSession> sharedSessions = sharedSessionRepository.findAllForParticipant(viewerUserId, statuses, pageable);

        Map<UUID, List<SessionParticipant>> participantsBySession = sessionParticipantRepository
                .findBySessionIdInOrderBySeatAsc(sharedSessions.map(SharedSession::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(SessionParticipant::getSessionId));

        Map<UUID, String> usernames = userProfileService.usernames(participantsBySession.values().stream()
                .flatMap(List::stream)
                .map(SessionParticipant::getUserId)
                .toList());

        return sharedSessions.map(sharedSession -> summary(sharedSession,
                participantsBySession.getOrDefault(sharedSession.getId(), List.of()), usernames,
                viewerUserId, sessionRunLock.isHeld(sharedSession.getId())));
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> messages(UUID sessionId, UUID viewerUserId) {
        SharedSession sharedSession = requireParticipant(sessionId, viewerUserId);

        return chatMessageService.sharedTranscript(sharedSession.getChatId());
    }

    /**
     * Applies what a run's agent reported. The turn's participant names become user ids; a question is
     * for the participants its form names, or failing that whoever the turn waits on. A run that
     * completed without a question clears the old one, so a button left over from it can no longer be
     * pressed. A failed or cancelled run leaves the session as it was: the agent kept its own state, and
     * the same message can simply be sent again.
     */
    @Transactional
    public SharedSessionSummary applyOutcome(UUID sessionId, UUID runId, TurnOutcome outcome, SessionTurnCollector collector) {
        SharedSession sharedSession = sharedSessionRepository.lockById(sessionId).orElseThrow(SharedSessionService::sessionNotFound);
        List<SessionParticipant> participants = participants(sessionId);

        if (!sharedSession.getStatus().acceptsMessages()) {
            log.info("Discarding run {} of shared session {}: the session is {}", runId, sessionId, sharedSession.getStatus());

            return summary(sharedSession, participants, null, false);
        }

        if (outcome == TurnOutcome.COMPLETED) {
            Map<String, UUID> userIdsByName = participants.stream()
                    .collect(Collectors.toMap(participant -> participant.getName().toLowerCase(Locale.ROOT),
                            SessionParticipant::getUserId));
            List<String> names = participants.stream().map(SessionParticipant::getName).toList();

            SessionTurn turn = collector.turn()
                    .map(turnState -> new SessionTurn(userIds(turnState.participants(), userIdsByName), turnState.next(), turnState.over()))
                    .orElse(null);

            SessionPrompt prompt = collector.prompt()
                    .map(collected -> {
                        List<UUID> forUserIds = userIds(A2AParticipantsExtension.forParticipants(collected.prompt().meta(), names), userIdsByName);

                        return new SessionPrompt(UUID.randomUUID(),
                                collected.taskId(),
                                collected.prompt().message(),
                                collected.prompt().requestedSchema(),
                                collected.prompt().meta(),
                                forUserIds.isEmpty() && turn != null ? turn.userIds() : forUserIds);
                    })
                    .orElse(null);

            sharedSession.setTurnState(turn);
            sharedSession.setPendingPrompt(prompt);

            if (turn != null) {
                sharedSession.setStatus(turn.over() ? SessionStatus.COMPLETED : SessionStatus.ACTIVE);
            }

            log.info("Run {} of shared session {} completed: next {}, waiting on {} participant(s), {}", runId, sessionId,
                    turn == null ? "unreported" : turn.next(),
                    turn == null ? 0 : turn.userIds().size(),
                    prompt == null ? "no question" : "a question");
        } else {
            log.info("Run {} of shared session {} ended {}: the session is unchanged", runId, sessionId, outcome);
        }

        sharedSession.setLastRunId(runId);
        sharedSession.setUpdated(ZonedDateTime.now(clock));

        return summary(sharedSessionRepository.save(sharedSession), participants, null, false);
    }

    /**
     * The session's state as a frame of its own stream, which every participant reads. Written outside
     * any run when a participant leaves, and inside one just ahead of its terminal frame.
     */
    public Mono<Void> publishState(UUID chatId, UUID streamUserId, SharedSessionSummary sharedSessionSummary) {
        return redisStreamService.publish(chatId, streamUserId, EventType.CUSTOM.value(),
                        new CustomEvent(SESSION_STATE, sharedSessionSummary, null, null))
                .then();
    }

    /**
     * Leaving closes the session for everyone: with two seats it cannot go on with one. The chat stays
     * for whoever has not left yet, read-only, and goes once everyone has.
     */
    @Transactional
    public void leave(UUID sessionId, UUID userId) {
        SharedSession sharedSession = sharedSessionRepository.lockById(sessionId).orElseThrow(SharedSessionService::sessionNotFound);
        List<SessionParticipant> participants = participants(sessionId);

        SessionParticipant leaving = participants.stream()
                .filter(participant -> participant.getUserId().equals(userId) && !participant.hasLeft())
                .findFirst()
                .orElseThrow(SharedSessionService::sessionNotFound);

        ZonedDateTime now = ZonedDateTime.now(clock);

        leaving.setLeftAt(now);
        sessionParticipantRepository.save(leaving);

        sharedSession.setStatus(SessionStatus.CLOSED);
        sharedSession.setPendingPrompt(null);
        sharedSession.setUpdated(now);
        sharedSessionRepository.save(sharedSession);

        UUID chatId = sharedSession.getChatId();

        log.info("Participant in seat {} left shared session {}", leaving.getSeat(), sessionId);

        a2aStickyAgentService.deactivateTask(chatId)
                .onErrorComplete()
                .subscribe();

        if (participants.stream().allMatch(SessionParticipant::hasLeft)) {
            log.info("Every participant left shared session {}; deleting its chat", sessionId);

            chatService.deleteSharedChat(chatId);
            return;
        }

        publishState(chatId, streamOwner(participants), summary(sharedSession, participants, null, false))
                .doOnError(error -> log.warn("Could not tell shared session {} that a participant left: {}", sessionId,
                        error.getClass().getSimpleName()))
                .onErrorComplete()
                .subscribe();
    }

    /**
     * Sessions nobody has written to for the idle window. An agent forgets its state on a timer of its
     * own; this keeps a session from accepting messages for state that is no longer there.
     */
    @Transactional
    public int expireIdle() {
        ZonedDateTime now = ZonedDateTime.now(clock);

        return sharedSessionRepository.expireIdle(now.minus(sharedSessionSettings.idle()), now);
    }

    SharedSessionSummary summary(SharedSession sharedSession, List<SessionParticipant> participants, UUID viewerUserId, boolean runActive) {
        Map<UUID, String> usernames = userProfileService.usernames(participants.stream().map(SessionParticipant::getUserId).toList());

        return summary(sharedSession, participants, usernames, viewerUserId, runActive);
    }

    private static SharedSessionSummary summary(SharedSession sharedSession,
                                                List<SessionParticipant> participants,
                                                Map<UUID, String> usernames,
                                                UUID viewerUserId,
                                                boolean runActive) {
        List<SessionParticipantSummary> participantSummaries = participants.stream()
                .map(participant -> new SessionParticipantSummary(participant.getUserId(),
                        participant.getSeat(),
                        participant.getName(),
                        usernames.get(participant.getUserId()),
                        participant.hasLeft()))
                .toList();

        SessionTurn turn = sharedSession.getTurnState();

        Boolean yourTurn = viewerUserId == null
                ? null
                : sharedSession.getStatus() == SessionStatus.ACTIVE && turn != null && turn.userIds().contains(viewerUserId);

        return new SharedSessionSummary(sharedSession.getId(),
                sharedSession.getChatId(),
                sharedSession.getAgentName(),
                sharedSession.getStatus(),
                participantSummaries,
                turn,
                sharedSession.getPendingPrompt() == null || !sharedSession.getStatus().acceptsMessages()
                        ? null
                        : sharedSession.getPendingPrompt().view(),
                yourTurn,
                runActive,
                sharedSession.getCreated(),
                sharedSession.getUpdated());
    }

    private static List<UUID> userIds(List<String> names, Map<String, UUID> userIdsByName) {
        return names.stream()
                .map(name -> userIdsByName.get(name.toLowerCase(Locale.ROOT)))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    static SessionException sessionNotFound() {
        return new SessionException(SessionErrorCode.SESSION_NOT_FOUND, "Shared session not found");
    }
}
