package com.solesonic.service.session;

import com.solesonic.config.session.SharedSessionSettings;
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
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a user's chat into a shared conversation, and lets its owner add people later. Sharing does not
 * convert the chat: it forks it into a new {@code SHARED} chat that the owner owns, so the private chat,
 * with everything personal attached to it, stays exactly as it was and survives the share ending.
 * <p>
 * Invitees are named by exact username. Every name that cannot be used is reported in one
 * {@code PARTICIPANT_NOT_FOUND}, and nothing is written unless all of them can.
 */
@Service
public class SharedConversationService {
    private static final Logger log = LoggerFactory.getLogger(SharedConversationService.class);

    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_USERNAME_CHARACTERS = 255;
    private static final int MAX_NAME_CHARACTERS = 100;

    private final SharedSessionSettings sharedSessionSettings;
    private final UserLlmKeyService userLlmKeyService;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileService userProfileService;
    private final ChatService chatService;
    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ActiveStreamTracker activeStreamTracker;
    private final ChatModelSelection chatModelSelection;
    private final SharedSessionRepository sharedSessionRepository;
    private final SessionParticipantRepository sessionParticipantRepository;
    private final SessionInviteRepository sessionInviteRepository;
    private final SessionInviteService sessionInviteService;
    private final SharedSessionService sharedSessionService;
    private final PushNotificationService pushNotificationService;
    private final SecurityEventLogger securityEventLogger;
    private final Clock clock;

    public SharedConversationService(SharedSessionSettings sharedSessionSettings,
                                     UserLlmKeyService userLlmKeyService,
                                     UserProfileRepository userProfileRepository,
                                     UserProfileService userProfileService,
                                     ChatService chatService,
                                     ChatRepository chatRepository,
                                     ChatMessageRepository chatMessageRepository,
                                     ActiveStreamTracker activeStreamTracker,
                                     ChatModelSelection chatModelSelection,
                                     SharedSessionRepository sharedSessionRepository,
                                     SessionParticipantRepository sessionParticipantRepository,
                                     SessionInviteRepository sessionInviteRepository,
                                     SessionInviteService sessionInviteService,
                                     SharedSessionService sharedSessionService,
                                     PushNotificationService pushNotificationService,
                                     SecurityEventLogger securityEventLogger,
                                     Clock clock) {
        this.sharedSessionSettings = sharedSessionSettings;
        this.userLlmKeyService = userLlmKeyService;
        this.userProfileRepository = userProfileRepository;
        this.userProfileService = userProfileService;
        this.chatService = chatService;
        this.chatRepository = chatRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.activeStreamTracker = activeStreamTracker;
        this.chatModelSelection = chatModelSelection;
        this.sharedSessionRepository = sharedSessionRepository;
        this.sessionParticipantRepository = sessionParticipantRepository;
        this.sessionInviteRepository = sessionInviteRepository;
        this.sessionInviteService = sessionInviteService;
        this.sharedSessionService = sharedSessionService;
        this.pushNotificationService = pushNotificationService;
        this.securityEventLogger = securityEventLogger;
        this.clock = clock;
    }

    /**
     * @throws SessionException when sharing is off, a username cannot be used, or the chat is mid-run
     */
    @Transactional
    public SharedSessionSummary create(UUID ownerUserId, SharedConversationRequest request) {
        SharedSessionSettings.Conversation conversation = requireEnabled();

        if (request == null) {
            throw new SessionException(SessionErrorCode.INVALID_INVITE, "Pick at least one person to share with.");
        }

        userLlmKeyService.requireKey(ownerUserId);

        Map<UUID, String> invitees = resolve(ownerUserId, request.usernames(), conversation.maxParticipants());

        if (invitees.size() > conversation.maxParticipants() - 1) {
            throw new SessionException(SessionErrorCode.TOO_MANY_PARTICIPANTS,
                    "A shared chat holds up to " + conversation.maxParticipants() + " people, you included.");
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        // Past-due invites would otherwise still count.
        sessionInviteRepository.expireDueFor(ownerUserId, now);
        requirePendingRoom(ownerUserId, invitees.size());

        if (sharedSessionRepository.countActiveConversationsOwnedBy(ownerUserId) >= conversation.maxActiveSessions()) {
            throw new SessionException(SessionErrorCode.SESSION_LIMIT_REACHED,
                    "You already have " + conversation.maxActiveSessions() + " shared chats going. End one before starting another.");
        }

        UUID sourceChatId = request.sourceChatId();
        String sourceName = null;

        if (sourceChatId != null) {
            chatService.requireOwned(sourceChatId);
            requireNotMidRun(ownerUserId, sourceChatId);

            sourceName = chatRepository.findByIdAndUserId(sourceChatId, ownerUserId).map(Chat::getName).orElse(null);
        }

        Chat chat = new Chat();
        chat.setUserId(ownerUserId);
        chat.setKind(ChatKind.SHARED);
        chat.setName(chatName(request.name(), sourceName));
        chat.setTimestamp(now);
        chat = chatRepository.save(chat);
        UUID chatId = chat.getId();

        if (sourceChatId != null) {
            int forked = chatMessageRepository.forkRecent(sourceChatId, chatId, ownerUserId, conversation.forkMaxMessages());

            log.info("Forked {} message(s) of chat {} into shared chat {}", forked, sourceChatId, chatId);

            copyModel(sourceChatId, chatId);
        }

        SharedSession sharedSession = new SharedSession();
        sharedSession.setId(UUID.randomUUID());
        sharedSession.setChatId(chatId);
        sharedSession.setMode(SessionMode.CONVERSATION);
        sharedSession.setAgentName(null);
        sharedSession.setSourceChatId(sourceChatId);
        sharedSession.setStatus(SessionStatus.ACTIVE);
        sharedSession.setCreated(now);
        sharedSession.setUpdated(now);
        sharedSession = sharedSessionRepository.save(sharedSession);

        UserProfile ownerProfile = userProfileService.profile(ownerUserId).orElse(null);
        ParticipantNames.Candidate ownerCandidate = ownerProfile == null
                ? new ParticipantNames.Candidate(null, null)
                : new ParticipantNames.Candidate(ownerProfile.displayName(), ownerProfile.username());

        SessionParticipant owner = new SessionParticipant();
        owner.setId(UUID.randomUUID());
        owner.setSessionId(sharedSession.getId());
        owner.setUserId(ownerUserId);
        owner.setSeat(SharedSessionService.STREAM_OWNER_SEAT);
        owner.setName(ParticipantNames.assign(List.of(ownerCandidate), sharedSessionSettings.maxNameCharacters()).getFirst());
        owner.setJoined(now);
        sessionParticipantRepository.save(owner);

        sendInvites(sharedSession.getId(), ownerUserId, invitees.keySet(), now);

        securityEventLogger.audit(SecurityEvent.SESSION_SHARED, ownerUserId, sharedSession.getId(), invitees.size());

        log.info("User {} shared chat {} as session {} with {} invitee(s)", ownerUserId, sourceChatId, sharedSession.getId(), invitees.size());

        return sharedSessionService.summary(sharedSession, sharedSessionService.participants(sharedSession.getId()), ownerUserId, false);
    }

    /**
     * The owner adds people to a conversation that is already running. Anyone else, including another
     * participant, is refused; someone who does not take part at all gets the {@code 404} every other
     * session endpoint gives. Someone who is already seated is refused by name, someone whose invite is
     * still open is not invited twice, and someone who left may be invited back.
     *
     * @return one invite per person named, new or still open
     */
    @Transactional
    public List<SessionInviteSummary> invite(UUID sessionId, UUID ownerUserId, SessionInvitesRequest request) {
        SharedSessionSettings.Conversation conversation = requireEnabled();

        sharedSessionService.requireSeated(sessionId, ownerUserId);

        // Held for the rest of the transaction, so two owners' requests (or an owner and an accept)
        // cannot both count the same free seat.
        SharedSession sharedSession = sharedSessionRepository.lockById(sessionId).orElseThrow(SharedSessionService::sessionNotFound);

        if (sharedSession.getMode() != SessionMode.CONVERSATION) {
            throw new SessionException(SessionErrorCode.INVALID_INVITE, "Only a shared chat takes more people.");
        }

        List<SessionParticipant> participants = sharedSessionService.participants(sessionId);

        if (!ownerUserId.equals(SharedSessionService.streamOwner(participants))) {
            throw new SessionException(SessionErrorCode.NOT_SESSION_OWNER, "Session not found");
        }

        if (!sharedSession.getStatus().acceptsMessages()) {
            throw new SessionException(SessionErrorCode.SESSION_CLOSED, "This session is over.");
        }

        Map<UUID, String> requested = resolve(ownerUserId, request == null ? null : request.usernames(), conversation.maxParticipants());

        Set<UUID> seated = participants.stream()
                .filter(participant -> !participant.hasLeft())
                .map(SessionParticipant::getUserId)
                .collect(Collectors.toSet());

        List<String> alreadySeated = requested.entrySet().stream()
                .filter(entry -> seated.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        if (!alreadySeated.isEmpty()) {
            throw new SessionException(SessionErrorCode.INVALID_INVITE, "Some of those people are already in this chat.", alreadySeated);
        }

        // Past-due invites would otherwise count toward the caps, be handed back as if just sent, and
        // block a fresh invite through the per-session unique index.
        ZonedDateTime now = ZonedDateTime.now(clock);
        sessionInviteRepository.expireDueFor(ownerUserId, now);

        List<SessionInvite> pending = sessionInviteRepository.findBySessionIdAndStatus(sessionId, SessionInviteStatus.PENDING);
        Map<UUID, SessionInvite> pendingByInvitee = new LinkedHashMap<>();
        pending.forEach(invite -> pendingByInvitee.put(invite.getInviteeUserId(), invite));

        List<UUID> newInvitees = requested.keySet().stream().filter(userId -> !pendingByInvitee.containsKey(userId)).toList();

        if (seated.size() + pending.size() + newInvitees.size() > conversation.maxParticipants()) {
            throw new SessionException(SessionErrorCode.TOO_MANY_PARTICIPANTS,
                    "A shared chat holds up to " + conversation.maxParticipants() + " people, the ones invited included.");
        }

        requirePendingRoom(ownerUserId, newInvitees.size());

        Map<UUID, SessionInvite> created = new LinkedHashMap<>();
        sendInvites(sessionId, ownerUserId, newInvitees, now).forEach(invite -> created.put(invite.getInviteeUserId(), invite));

        if (!newInvitees.isEmpty()) {
            securityEventLogger.audit(SecurityEvent.SESSION_INVITED, ownerUserId, sessionId, newInvitees.size());
        }

        List<SessionInvite> answer = requested.keySet().stream()
                .map(userId -> created.containsKey(userId) ? created.get(userId) : pendingByInvitee.get(userId))
                .toList();

        return sessionInviteService.summaries(answer);
    }

    /**
     * The inviter's invites waiting across every session, as the legacy invite counts them, so a share is
     * not a way round the cap.
     */
    private void requirePendingRoom(UUID inviterUserId, int newInvites) {
        long waiting = sessionInviteRepository.countByInviterUserIdAndStatus(inviterUserId, SessionInviteStatus.PENDING);

        if (waiting + newInvites > sharedSessionSettings.maxPendingInvites()) {
            throw new SessionException(SessionErrorCode.TOO_MANY_INVITES, "You have too many invites waiting for an answer.");
        }
    }

    private SharedSessionSettings.Conversation requireEnabled() {
        SharedSessionSettings.Conversation conversation = sharedSessionSettings.conversation();

        if (!conversation.enabled()) {
            throw new SessionException(SessionErrorCode.SESSION_NOT_FOUND, "Shared conversations are not available.");
        }

        return conversation;
    }

    /**
     * The people named, as user ids in the order named, without the caller. Names are stripped and
     * deduplicated ignoring case. Every name that is not an active account is reported at once.
     */
    private Map<UUID, String> resolve(UUID ownerUserId, List<String> usernames, int maxParticipants) {
        if (usernames == null || usernames.isEmpty()) {
            throw new SessionException(SessionErrorCode.INVALID_INVITE, "Pick at least one person.");
        }

        Map<String, String> distinct = new LinkedHashMap<>();

        for (String username : usernames) {
            String stripped = username == null ? "" : username.strip();

            if (stripped.isEmpty() || stripped.length() > MAX_USERNAME_CHARACTERS) {
                throw new SessionException(SessionErrorCode.INVALID_INVITE, "Every invitee needs a username.");
            }

            distinct.putIfAbsent(stripped.toLowerCase(Locale.ROOT), stripped);
        }

        // Bounds the lookups: more names than seats can never all be seated.
        if (distinct.size() > maxParticipants) {
            throw new SessionException(SessionErrorCode.TOO_MANY_PARTICIPANTS,
                    "A shared chat holds up to " + maxParticipants + " people, you included.");
        }

        Map<UUID, String> resolved = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();

        for (String username : distinct.values()) {
            userProfileRepository.findActiveUserIdByUsername(username).ifPresentOrElse(
                    userId -> resolved.putIfAbsent(userId, username),
                    () -> unknown.add(username));
        }

        if (!unknown.isEmpty()) {
            throw new SessionException(SessionErrorCode.PARTICIPANT_NOT_FOUND, "Some of those usernames do not belong to anyone.", unknown);
        }

        resolved.remove(ownerUserId);

        if (resolved.isEmpty()) {
            throw new SessionException(SessionErrorCode.INVALID_INVITE, "Pick at least one person other than yourself.");
        }

        return resolved;
    }

    private void requireNotMidRun(UUID ownerUserId, UUID chatId) {
        UUID streaming = activeStreamTracker.get(ownerUserId).block(LOOKUP_TIMEOUT);

        if (chatId.equals(streaming)) {
            throw new SessionException(SessionErrorCode.CHAT_STREAMING, "Wait for the reply to finish before sharing this chat.");
        }
    }

    /**
     * The model picked for the source chat carries over, once the share has committed so a rolled-back
     * share leaves no key behind. A failure leaves the new chat on the default model.
     */
    private void copyModel(UUID sourceChatId, UUID chatId) {
        AfterCommit.run(() -> {
            try {
                String model = chatModelSelection.get(sourceChatId).block(LOOKUP_TIMEOUT);

                if (model != null) {
                    chatModelSelection.set(chatId, model).block(LOOKUP_TIMEOUT);
                }
            } catch (RuntimeException exception) {
                log.warn("Could not copy the model of chat {} to shared chat {}: {}", sourceChatId, chatId, exception.getClass().getSimpleName());
            }
        });
    }

    private static String chatName(String requested, String sourceName) {
        String name = StringUtils.defaultIfBlank(StringUtils.strip(requested), sourceName);

        return name == null || name.length() <= MAX_NAME_CHARACTERS ? name : name.substring(0, MAX_NAME_CHARACTERS);
    }

    private List<SessionInvite> sendInvites(UUID sessionId, UUID inviterUserId, Iterable<UUID> inviteeUserIds, ZonedDateTime now) {
        List<SessionInvite> sent = new ArrayList<>();

        for (UUID inviteeUserId : inviteeUserIds) {
            SessionInvite sessionInvite = new SessionInvite();
            sessionInvite.setId(UUID.randomUUID());
            sessionInvite.setInviterUserId(inviterUserId);
            sessionInvite.setInviteeUserId(inviteeUserId);
            sessionInvite.setAgentName(null);
            sessionInvite.setSessionId(sessionId);
            sessionInvite.setStatus(SessionInviteStatus.PENDING);
            sessionInvite.setCreated(now);
            sessionInvite.setExpires(now.plus(sharedSessionSettings.inviteTimeToLive()));
            sessionInvite = sessionInviteRepository.save(sessionInvite);
            sent.add(sessionInvite);

            UUID inviteId = sessionInvite.getId();
            AfterCommit.run(() -> pushNotificationService.push(inviteeUserId, new PushEvent.SessionInvited(inviteId)));
        }

        return sent;
    }
}
