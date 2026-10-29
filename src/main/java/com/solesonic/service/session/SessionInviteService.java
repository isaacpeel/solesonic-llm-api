package com.solesonic.service.session;

import com.solesonic.config.a2a.A2AAgentRegistry;
import com.solesonic.config.session.SharedSessionSettings;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionInvite;
import com.solesonic.model.session.SessionInviteRequest;
import com.solesonic.model.session.SessionInviteStatus;
import com.solesonic.model.session.SessionInviteSummary;
import com.solesonic.model.session.SessionMessageAccepted;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.model.user.UserProfile;
import com.solesonic.repository.session.SessionInviteRepository;
import com.solesonic.repository.user.UserProfileRepository;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.user.UserProfileService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Invites to a shared session with an A2A agent. A user is invited by exact username; an unknown name,
 * a removed account and the caller's own name are all the same {@code 404}. Every participant must
 * hold a model key, since the agent spends model calls on their behalf, and the agent must be one the
 * deployment allows to be shared.
 * <p>
 * Accepting creates the session and sends its opening message, if the invite has one, once the
 * transaction has committed, so the run never reads a session that might yet roll back.
 */
@Service
public class SessionInviteService {
    private static final Logger log = LoggerFactory.getLogger(SessionInviteService.class);

    private static final int MAX_USERNAME_CHARACTERS = 255;

    private final SessionInviteRepository sessionInviteRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileService userProfileService;
    private final UserLlmKeyService userLlmKeyService;
    private final A2AAgentRegistry a2aAgentRegistry;
    private final SharedSessionService sharedSessionService;
    private final SessionMessageService sessionMessageService;
    private final PushNotificationService pushNotificationService;
    private final SharedSessionSettings sharedSessionSettings;
    private final Clock clock;

    public SessionInviteService(SessionInviteRepository sessionInviteRepository,
                                UserProfileRepository userProfileRepository,
                                UserProfileService userProfileService,
                                UserLlmKeyService userLlmKeyService,
                                A2AAgentRegistry a2aAgentRegistry,
                                SharedSessionService sharedSessionService,
                                SessionMessageService sessionMessageService,
                                PushNotificationService pushNotificationService,
                                SharedSessionSettings sharedSessionSettings,
                                Clock clock) {
        this.sessionInviteRepository = sessionInviteRepository;
        this.userProfileRepository = userProfileRepository;
        this.userProfileService = userProfileService;
        this.userLlmKeyService = userLlmKeyService;
        this.a2aAgentRegistry = a2aAgentRegistry;
        this.sharedSessionService = sharedSessionService;
        this.sessionMessageService = sessionMessageService;
        this.pushNotificationService = pushNotificationService;
        this.sharedSessionSettings = sharedSessionSettings;
        this.clock = clock;
    }

    @Transactional
    public SessionInviteSummary invite(UUID inviterUserId, SessionInviteRequest sessionInviteRequest) {
        if (sessionInviteRequest == null || StringUtils.isBlank(sessionInviteRequest.username())
                || sessionInviteRequest.username().length() > MAX_USERNAME_CHARACTERS) {
            throw new SessionException(SessionErrorCode.INVALID_INVITE, "An invite needs a username.");
        }

        if (!sharedSessionSettings.allowsAgent(sessionInviteRequest.agentName())) {
            throw new SessionException(SessionErrorCode.INVALID_INVITE, "That agent cannot be shared.");
        }

        String openingMessage = StringUtils.trimToNull(sessionInviteRequest.openingMessage());

        if (openingMessage != null && openingMessage.codePointCount(0, openingMessage.length()) > sharedSessionSettings.maxMessageCharacters()) {
            throw new SessionException(SessionErrorCode.MESSAGE_TOO_LONG,
                    "Messages are limited to " + sharedSessionSettings.maxMessageCharacters() + " characters.");
        }

        UUID inviteeUserId = userProfileRepository.findActiveUserIdByUsername(sessionInviteRequest.username().strip())
                .filter(userId -> !userId.equals(inviterUserId))
                .orElseThrow(() -> new SessionException(SessionErrorCode.PARTICIPANT_NOT_FOUND, "No user with that username."));

        userLlmKeyService.requireKey(inviterUserId);
        requireAgent(sessionInviteRequest.agentName());

        if (sessionInviteRepository.existsPendingBetween(inviterUserId, inviteeUserId)) {
            throw new SessionException(SessionErrorCode.INVITE_PENDING, "There is already an invite waiting between you two.");
        }

        if (sessionInviteRepository.countByInviterUserIdAndStatus(inviterUserId, SessionInviteStatus.PENDING) >= sharedSessionSettings.maxPendingInvites()) {
            throw new SessionException(SessionErrorCode.TOO_MANY_INVITES, "You have too many invites waiting for an answer.");
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        SessionInvite sessionInvite = new SessionInvite();
        sessionInvite.setId(UUID.randomUUID());
        sessionInvite.setInviterUserId(inviterUserId);
        sessionInvite.setInviteeUserId(inviteeUserId);
        sessionInvite.setAgentName(sessionInviteRequest.agentName());
        sessionInvite.setOpeningMessage(openingMessage);
        sessionInvite.setStatus(SessionInviteStatus.PENDING);
        sessionInvite.setCreated(now);
        sessionInvite.setExpires(now.plus(sharedSessionSettings.inviteTimeToLive()));
        sessionInvite = sessionInviteRepository.save(sessionInvite);

        log.info("Shared-session invite {} sent", sessionInvite.getId());

        UUID inviteId = sessionInvite.getId();
        afterCommit(() -> pushNotificationService.push(inviteeUserId, new PushEvent.SessionInvited(inviteId)));

        return summaries(List.of(sessionInvite)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<SessionInviteSummary> incoming(UUID userId) {
        return summaries(sessionInviteRepository.findByInviteeUserIdAndStatusOrderByCreatedDesc(userId, SessionInviteStatus.PENDING));
    }

    @Transactional(readOnly = true)
    public List<SessionInviteSummary> outgoing(UUID userId) {
        return summaries(sessionInviteRepository.findByInviterUserIdAndStatusOrderByCreatedDesc(userId, SessionInviteStatus.PENDING));
    }

    @Transactional(readOnly = true)
    public SessionInviteSummary get(UUID inviteId, UUID userId) {
        return sessionInviteRepository.findForParticipant(inviteId, userId)
                .map(sessionInvite -> summaries(List.of(sessionInvite)).getFirst())
                .orElseThrow(SessionInviteService::inviteNotFound);
    }

    @Transactional
    public SharedSessionSummary accept(UUID inviteId, UUID inviteeUserId, Authentication authentication) {
        SessionInvite sessionInvite = pending(sessionInviteRepository.lockForInvitee(inviteId, inviteeUserId)
                .orElseThrow(SessionInviteService::inviteNotFound));

        userLlmKeyService.requireKey(inviteeUserId);
        requireAgent(sessionInvite.getAgentName());

        SharedSession sharedSession = sharedSessionService.create(sessionInvite);

        sessionInvite.setStatus(SessionInviteStatus.ACCEPTED);
        sessionInvite.setSessionId(sharedSession.getId());
        sessionInvite.setDecided(ZonedDateTime.now(clock));
        sessionInviteRepository.save(sessionInvite);

        log.info("Accepted an invite, starting shared session {}", sharedSession.getId());

        String openingMessage = sessionInvite.getOpeningMessage();

        if (openingMessage != null) {
            afterCommit(() -> openingMessage(sharedSession, inviteeUserId, openingMessage, authentication));
        }

        return sharedSessionService.summary(sharedSession, sharedSessionService.participants(sharedSession.getId()), inviteeUserId,
                openingMessage != null);
    }

    /**
     * Runs after the accept has committed, so a failure here cannot undo it: the session exists, and a
     * participant can send the first message themselves.
     */
    private void openingMessage(SharedSession sharedSession, UUID inviteeUserId, String openingMessage, Authentication authentication) {
        try {
            SessionMessageAccepted opened = sessionMessageService.openingMessage(sharedSession, inviteeUserId, openingMessage, authentication);

            log.debug("Shared session {} opened with run {}", sharedSession.getId(), opened.runId());
        } catch (RuntimeException exception) {
            log.warn("Shared session {} could not send its opening message: {}", sharedSession.getId(), exception.getClass().getSimpleName());
        }
    }

    @Transactional
    public void decline(UUID inviteId, UUID inviteeUserId) {
        SessionInvite sessionInvite = pending(sessionInviteRepository.lockForInvitee(inviteId, inviteeUserId)
                .orElseThrow(SessionInviteService::inviteNotFound));

        decide(sessionInvite, SessionInviteStatus.DECLINED);
    }

    @Transactional
    public void cancel(UUID inviteId, UUID inviterUserId) {
        SessionInvite sessionInvite = pending(sessionInviteRepository.lockForInviter(inviteId, inviterUserId)
                .orElseThrow(SessionInviteService::inviteNotFound));

        decide(sessionInvite, SessionInviteStatus.CANCELLED);
    }

    @Transactional
    public int expire() {
        return sessionInviteRepository.expire(ZonedDateTime.now(clock));
    }

    /**
     * An invite past its expiry that the sweep has not reached yet is treated as the sweep would
     * treat it.
     */
    private SessionInvite pending(SessionInvite sessionInvite) {
        if (sessionInvite.getStatus() == SessionInviteStatus.PENDING && sessionInvite.getExpires().isBefore(ZonedDateTime.now(clock))) {
            decide(sessionInvite, SessionInviteStatus.EXPIRED);
        }

        if (sessionInvite.getStatus() != SessionInviteStatus.PENDING) {
            throw new SessionException(SessionErrorCode.INVITE_NOT_PENDING, "That invite is no longer open.");
        }

        return sessionInvite;
    }

    private void decide(SessionInvite sessionInvite, SessionInviteStatus status) {
        sessionInvite.setStatus(status);
        sessionInvite.setDecided(ZonedDateTime.now(clock));
        sessionInviteRepository.save(sessionInvite);

        log.info("Shared-session invite {} {}", sessionInvite.getId(), status);
    }

    private void requireAgent(String agentName) {
        if (a2aAgentRegistry.agent(agentName).isEmpty()) {
            throw new SessionException(SessionErrorCode.AGENT_UNAVAILABLE, "That agent is not available right now. Try again later.");
        }
    }

    private List<SessionInviteSummary> summaries(List<SessionInvite> sessionInvites) {
        Map<UUID, UserProfile> profiles = userProfileService.profiles(sessionInvites.stream()
                .flatMap(sessionInvite -> Stream.of(sessionInvite.getInviterUserId(), sessionInvite.getInviteeUserId()))
                .toList());

        return sessionInvites.stream()
                .map(sessionInvite -> {
                    UserProfile inviter = profiles.get(sessionInvite.getInviterUserId());
                    UserProfile invitee = profiles.get(sessionInvite.getInviteeUserId());

                    return new SessionInviteSummary(sessionInvite.getId(),
                            sessionInvite.getInviterUserId(),
                            inviter == null ? null : inviter.username(),
                            inviter == null ? null : inviter.displayName(),
                            sessionInvite.getInviteeUserId(),
                            invitee == null ? null : invitee.username(),
                            invitee == null ? null : invitee.displayName(),
                            sessionInvite.getAgentName(),
                            sessionInvite.getOpeningMessage(),
                            sessionInvite.getStatus(),
                            sessionInvite.getSessionId(),
                            sessionInvite.getCreated(),
                            sessionInvite.getExpires());
                })
                .toList();
    }

    private static void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }

    private static SessionException inviteNotFound() {
        return new SessionException(SessionErrorCode.INVITE_NOT_FOUND, "Invite not found");
    }
}
