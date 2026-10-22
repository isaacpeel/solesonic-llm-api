package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.model.interest.AgentType;
import com.solesonic.model.interest.InterestOffer;
import com.solesonic.model.interest.InterestScan;
import com.solesonic.model.interest.OfferStatus;
import com.solesonic.model.interest.ScanStatus;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestOfferRepository;
import com.solesonic.repository.interest.InterestScanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The discovery cursor's bookkeeping, each step its own short transaction so that no lock is held
 * across a model call. Mirrors {@code ChatMemoryReflectionService}.
 */
@Service
public class InterestScanService {
    private static final Logger log = LoggerFactory.getLogger(InterestScanService.class);

    /**
     * How long a claim may sit {@code IN_PROGRESS} before another instance may take it: far longer
     * than one model call, so only a claim whose instance died is taken twice.
     */
    static final Duration STALE_CLAIM = Duration.ofHours(1);

    /**
     * How long a user whose discovery failed waits before it is tried again, so a model that keeps
     * failing is not called for them on every tick.
     */
    static final Duration RETRY_DELAY = Duration.ofHours(1);

    private final InterestScanRepository interestScanRepository;
    private final InterestOfferRepository interestOfferRepository;
    private final InterestAgentRepository interestAgentRepository;
    private final InterestSettings interestSettings;
    private final Clock clock;

    public InterestScanService(InterestScanRepository interestScanRepository,
                               InterestOfferRepository interestOfferRepository,
                               InterestAgentRepository interestAgentRepository,
                               InterestSettings interestSettings,
                               Clock clock) {
        this.interestScanRepository = interestScanRepository;
        this.interestOfferRepository = interestOfferRepository;
        this.interestAgentRepository = interestAgentRepository;
        this.interestSettings = interestSettings;
        this.clock = clock;
    }

    @Transactional
    public List<InterestScan> claim() {
        interestScanRepository.seedMissing();

        ZonedDateTime now = ZonedDateTime.now(clock);

        List<InterestScan> claimed = interestScanRepository.findClaimable(now.minus(STALE_CLAIM),
                now.minus(RETRY_DELAY),
                now.minus(interestSettings.offerCooldown()),
                interestSettings.maxAgentsPerUser(),
                interestSettings.discoveryBatchSize());

        for (InterestScan interestScan : claimed) {
            interestScan.setStatus(ScanStatus.IN_PROGRESS);
            interestScan.setUpdated(now);
        }

        return interestScanRepository.saveAll(claimed);
    }

    /**
     * Writes the result of one user's discovery and advances the cursor, in one transaction that first
     * re-locks the scan row. The result is discarded when the row is no longer the claim it was read
     * under — gone, or no longer in progress — and the candidate alone is dropped when the user has
     * since been asked something else or gained an agent for that topic: the cursor still advances.
     *
     * @param candidate the best valid candidate, or null when there was none
     * @return the offer created, which is the caller's to push once this has committed
     */
    @Transactional
    public Outcome complete(UUID userId, ZonedDateTime scannedThrough, ValidCandidate candidate) {
        Optional<InterestScan> locked = interestScanRepository.lockByUserId(userId);

        if (locked.isEmpty() || locked.get().getStatus() != ScanStatus.IN_PROGRESS) {
            log.info("Discovery for user {} was completed elsewhere meanwhile; discarding this result", userId);
            return Outcome.DISCARDED;
        }

        InterestScan interestScan = locked.get();
        ZonedDateTime now = ZonedDateTime.now(clock);
        UUID offerId = candidate != null && stillOfferable(userId, candidate, now) ? offer(userId, candidate, now) : null;

        if (offerId != null) {
            interestScan.setLastOffered(now);
        }

        interestScan.setScannedThrough(scannedThrough);
        interestScan.setStatus(ScanStatus.COMPLETED);
        interestScan.setAttempts(0);
        interestScan.setUpdated(now);
        interestScanRepository.save(interestScan);

        return new Outcome(true, offerId);
    }

    /**
     * A failure never writes an offer; it only counts against the user, who is not retried once the
     * count reaches {@code maxAttempts} until their memories change again.
     */
    @Transactional
    public void recordFailure(UUID userId) {
        interestScanRepository.lockByUserId(userId).ifPresent(interestScan -> {
            int attempts = interestScan.getAttempts() + 1;

            interestScan.setAttempts(attempts);
            interestScan.setStatus(attempts >= interestSettings.discoveryMaxAttempts() ? ScanStatus.FAILED : ScanStatus.PENDING);
            interestScan.setUpdated(ZonedDateTime.now(clock));

            interestScanRepository.save(interestScan);
        });
    }

    private boolean stillOfferable(UUID userId, ValidCandidate candidate, ZonedDateTime now) {
        if (interestOfferRepository.existsByUserIdAndStatus(userId, OfferStatus.PENDING)) {
            return false;
        }

        if (interestAgentRepository.existsByUserIdAndTopicKey(userId, candidate.topicKey())) {
            return false;
        }

        return !interestOfferRepository.findBlockedTopicKeys(userId, now.minus(interestSettings.declineCooldown()))
                .contains(candidate.topicKey());
    }

    private UUID offer(UUID userId, ValidCandidate candidate, ZonedDateTime now) {
        InterestOffer offer = new InterestOffer();
        offer.setUserId(userId);
        offer.setAgentType(AgentType.NEWS);
        offer.setTopicKey(candidate.topicKey());
        offer.setLabel(candidate.label());
        offer.setQuery(candidate.query());
        offer.setRationale(candidate.rationale());
        offer.setSourceMemoryIds(candidate.memoryIds());
        offer.setConfidence(candidate.confidence());
        offer.setStatus(OfferStatus.PENDING);
        offer.setCreated(now);
        offer.setExpires(now.plus(interestSettings.offerTimeToLive()));

        return interestOfferRepository.save(offer).getId();
    }

    /**
     * @param applied false when the result was discarded
     * @param offerId the offer created, or null
     */
    public record Outcome(boolean applied, UUID offerId) {
        static final Outcome DISCARDED = new Outcome(false, null);
    }
}
