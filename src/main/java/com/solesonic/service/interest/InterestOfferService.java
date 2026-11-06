package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.exception.interest.InterestException;
import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.interest.AgentCadence;
import com.solesonic.model.interest.AgentStatus;
import com.solesonic.model.interest.InterestAcceptRequest;
import com.solesonic.model.interest.InterestAgent;
import com.solesonic.model.interest.InterestAgentSummary;
import com.solesonic.model.interest.InterestOffer;
import com.solesonic.model.interest.InterestOfferSummary;
import com.solesonic.model.interest.OfferStatus;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.repository.chat.ChatGroupRepository;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestOfferRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.security.ResourceOwnershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.security.SecurityConfig.ROLE;

/**
 * A user's answer to an offer. Accepting is the only way an agent comes to exist, and it runs on the
 * owner's own request: discovery creates offers, never agents.
 * <p>
 * The chat group is written through its repository rather than {@code ChatGroupService}, whose
 * dependency on {@code ChatService} would close a cycle back through {@code UserMemoryService}. The
 * owner is the already-checked path user either way.
 */
@Service
public class InterestOfferService {
    private static final Logger log = LoggerFactory.getLogger(InterestOfferService.class);

    /**
     * The role the MCP server's web-search tools require. Background runs search on the application's
     * client credentials, so this is checked once, here, against the user's own token.
     */
    public static final String WEB_SEARCH_AUTHORITY = ROLE + "mcp-web-search";

    static final String CHAT_GROUP_PREFIX = "News: ";

    private static final Set<MemoryStatus> LIVE_STATUSES = EnumSet.of(MemoryStatus.ACTIVE, MemoryStatus.PENDING);

    private final InterestOfferRepository interestOfferRepository;
    private final InterestAgentRepository interestAgentRepository;
    private final UserMemoryRepository userMemoryRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final ResourceOwnershipService resourceOwnershipService;
    private final InterestSettings interestSettings;
    private final Clock clock;

    public InterestOfferService(InterestOfferRepository interestOfferRepository,
                                InterestAgentRepository interestAgentRepository,
                                UserMemoryRepository userMemoryRepository,
                                ChatGroupRepository chatGroupRepository,
                                ResourceOwnershipService resourceOwnershipService,
                                InterestSettings interestSettings,
                                Clock clock) {
        this.interestOfferRepository = interestOfferRepository;
        this.interestAgentRepository = interestAgentRepository;
        this.userMemoryRepository = userMemoryRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.resourceOwnershipService = resourceOwnershipService;
        this.interestSettings = interestSettings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<InterestOfferSummary> pending(UUID userId) {
        return interestOfferRepository.findByUserIdAndStatusAndExpiresAfterOrderByCreatedDesc(userId, OfferStatus.PENDING, now())
                .stream()
                .map(InterestOfferSummary::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public InterestOfferSummary get(UUID userId, UUID offerId) {
        return interestOfferRepository.findByIdAndUserId(offerId, userId)
                .map(InterestOfferSummary::of)
                .orElseThrow(InterestException::offerNotFound);
    }

    /**
     * One transaction: the agent and its chat group exist and the offer is accepted, or nothing
     * happened. Not rolled back for an interest refusal, so an offer found expired or withdrawn on the
     * way stays marked as such. No chat is created here; the first chat is the first update.
     */
    @Transactional(noRollbackFor = InterestException.class)
    public InterestAgentSummary accept(UUID userId, UUID offerId, InterestAcceptRequest interestAcceptRequest) {
        ZonedDateTime now = now();
        InterestOffer offer = undecided(userId, offerId, now);

        if (!resourceOwnershipService.hasAuthority(WEB_SEARCH_AUTHORITY)) {
            throw InterestException.notPermitted();
        }

        if (interestAgentRepository.countByUserId(userId) >= interestSettings.maxAgentsPerUser()) {
            throw InterestException.agentLimitReached();
        }

        if (!hasLiveSource(userId, offer) || interestAgentRepository.existsByUserIdAndTopicKey(userId, offer.getTopicKey())) {
            decide(offer, OfferStatus.WITHDRAWN, now);
            throw InterestException.offerWithdrawn();
        }

        ChatGroup chatGroup = new ChatGroup();
        chatGroup.setUserId(userId);
        chatGroup.setName(CHAT_GROUP_PREFIX + offer.getLabel());
        chatGroup.setTimestamp(now);
        ChatGroup savedChatGroup = chatGroupRepository.save(chatGroup);

        InterestAgent agent = new InterestAgent();
        agent.setUserId(userId);
        agent.setOfferId(offer.getId());
        agent.setAgentType(offer.getAgentType());
        agent.setTopicKey(offer.getTopicKey());
        agent.setLabel(offer.getLabel());
        agent.setQuery(offer.getQuery());
        agent.setChatGroupId(savedChatGroup.getId());
        agent.setCadence(cadence(interestAcceptRequest));
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setNextRunAt(now);
        agent.setFailures(0);
        agent.setCreated(now);
        agent.setUpdated(now);
        InterestAgent saved = interestAgentRepository.save(agent);

        decide(offer, OfferStatus.ACCEPTED, now);

        log.info("User {} accepted offer {}: agent {}", userId, offerId, saved.getId());

        return InterestAgentSummary.of(saved);
    }

    /**
     * The topic is not offered again until {@code decline-cooldown-days} have passed: discovery reads
     * the decision back through {@link InterestOfferRepository#findBlockedTopicKeys}.
     */
    @Transactional
    public void decline(UUID userId, UUID offerId) {
        InterestOffer offer = interestOfferRepository.lockByIdAndUserId(offerId, userId)
                .orElseThrow(InterestException::offerNotFound);

        if (offer.getStatus() != OfferStatus.PENDING) {
            throw InterestException.offerAlreadyDecided();
        }

        decide(offer, OfferStatus.DECLINED, now());

        log.info("User {} declined offer {}", userId, offerId);
    }

    @Transactional
    public int expireDue() {
        return interestOfferRepository.expireDue(now());
    }

    private InterestOffer undecided(UUID userId, UUID offerId, ZonedDateTime now) {
        InterestOffer offer = interestOfferRepository.lockByIdAndUserId(offerId, userId)
                .orElseThrow(InterestException::offerNotFound);

        if (offer.getStatus() != OfferStatus.PENDING) {
            throw InterestException.offerAlreadyDecided();
        }

        if (!offer.getExpires().isAfter(now)) {
            decide(offer, OfferStatus.EXPIRED, now);
            throw InterestException.offerExpired();
        }

        return offer;
    }

    private boolean hasLiveSource(UUID userId, InterestOffer offer) {
        List<UUID> sourceMemoryIds = offer.getSourceMemoryIds();

        if (sourceMemoryIds == null || sourceMemoryIds.isEmpty()) {
            return false;
        }

        return userMemoryRepository.countByUserIdAndIdInAndStatusIn(userId, sourceMemoryIds, LIVE_STATUSES) > 0;
    }

    private void decide(InterestOffer offer, OfferStatus status, ZonedDateTime now) {
        offer.setStatus(status);
        offer.setDecided(now);
        interestOfferRepository.save(offer);
    }

    private static AgentCadence cadence(InterestAcceptRequest interestAcceptRequest) {
        if (interestAcceptRequest == null || interestAcceptRequest.cadence() == null) {
            return AgentCadence.DAILY;
        }

        return interestAcceptRequest.cadence();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
