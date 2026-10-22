package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.model.interest.InterestCandidates;
import com.solesonic.model.interest.InterestScan;
import com.solesonic.model.memory.MemoryKind;
import com.solesonic.model.memory.MemoryStatus;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.push.PushEvent;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestOfferRepository;
import com.solesonic.repository.memory.UserMemoryRepository;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.util.PromptQuoting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.solesonic.config.openai.MemoryOpenAiConfig.MEMORY_CHAT_MODEL;

/**
 * Reads what memory has learned about each user and, at most one question at a time, offers them a
 * news agent for something they follow. Discovery creates offers, never agents.
 * <p>
 * It works on exactly one user at a time: the owner comes from the claimed scan row, the memories the
 * model is shown are that owner's active facts and preferences alone, and the validator accepts only
 * memory ids from that set. Off the request path, sequential, one model call at a time.
 */
@Service
public class InterestDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(InterestDiscoveryService.class);

    private static final Set<MemoryKind> DISCOVERABLE_KINDS = Set.of(MemoryKind.FACT, MemoryKind.PREFERENCE);

    private final InterestScanService interestScanService;
    private final InterestOfferService interestOfferService;
    private final InterestOfferWithdrawal interestOfferWithdrawal;
    private final UserMemoryRepository userMemoryRepository;
    private final InterestAgentRepository interestAgentRepository;
    private final InterestOfferRepository interestOfferRepository;
    private final InterestCandidateValidator interestCandidateValidator;
    private final PushNotificationService pushNotificationService;
    private final InterestSettings interestSettings;
    private final Clock clock;
    private final ChatClient memoryChatClient;
    private final String discoveryPrompt;

    public InterestDiscoveryService(InterestScanService interestScanService,
                                    InterestOfferService interestOfferService,
                                    InterestOfferWithdrawal interestOfferWithdrawal,
                                    UserMemoryRepository userMemoryRepository,
                                    InterestAgentRepository interestAgentRepository,
                                    InterestOfferRepository interestOfferRepository,
                                    InterestCandidateValidator interestCandidateValidator,
                                    PushNotificationService pushNotificationService,
                                    InterestSettings interestSettings,
                                    Clock clock,
                                    @Qualifier(MEMORY_CHAT_MODEL) ChatModel memoryChatModel,
                                    @Value("classpath:prompts/interest-discovery-prompt.st") Resource discoveryPromptResource) {
        this.interestScanService = interestScanService;
        this.interestOfferService = interestOfferService;
        this.interestOfferWithdrawal = interestOfferWithdrawal;
        this.userMemoryRepository = userMemoryRepository;
        this.interestAgentRepository = interestAgentRepository;
        this.interestOfferRepository = interestOfferRepository;
        this.interestCandidateValidator = interestCandidateValidator;
        this.pushNotificationService = pushNotificationService;
        this.interestSettings = interestSettings;
        this.clock = clock;
        this.memoryChatClient = ChatClient.create(memoryChatModel);
        this.discoveryPrompt = read(discoveryPromptResource);
    }

    /**
     * Expires and withdraws stale offers first, so a user whose question lapsed is claimable on this
     * same tick, then discovers for a batch of users.
     */
    public DiscoveryRun discoverEligible() {
        sweep();

        List<InterestScan> claimed = interestScanService.claim();
        DiscoveryRun run = new DiscoveryRun();

        for (InterestScan interestScan : claimed) {
            discover(interestScan.getUserId(), run);
        }

        if (!claimed.isEmpty()) {
            log.info("Interest discovery run over {} user(s): {}", claimed.size(), run);
        }

        return run;
    }

    private void sweep() {
        try {
            int expired = interestOfferService.expireDue();
            int withdrawn = interestOfferWithdrawal.withdrawAllOrphaned();

            if (expired > 0 || withdrawn > 0) {
                log.info("Interest offers swept: {} expired, {} withdrawn", expired, withdrawn);
            }
        } catch (RuntimeException exception) {
            log.warn("Could not sweep interest offers ({})", exception.getClass().getSimpleName());
        }
    }

    private void discover(UUID userId, DiscoveryRun run) {
        try {
            List<UserMemory> memories = userMemoryRepository.findByUserIdAndStatusInOrderByCreatedAsc(userId, Set.of(MemoryStatus.ACTIVE))
                    .stream()
                    .filter(memory -> userId.equals(memory.getUserId()))
                    .filter(memory -> memory.getStatus() == MemoryStatus.ACTIVE)
                    .filter(memory -> DISCOVERABLE_KINDS.contains(memory.getKind()))
                    .toList();

            if (memories.isEmpty()) {
                interestScanService.complete(userId, null, null);
                run.nothing();
                return;
            }

            ZonedDateTime scannedThrough = memories.stream()
                    .map(UserMemory::getUpdated)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();

            Set<String> blocked = blocked(userId);

            InterestCandidates candidates = memoryChatClient.prompt()
                    .system(discoveryPrompt)
                    .user(render(memories, blocked))
                    .call()
                    .entity(InterestCandidates.class);

            ValidCandidate best = interestCandidateValidator.validate(memories, blocked, candidates, run).orElse(null);

            InterestScanService.Outcome outcome = interestScanService.complete(userId, scannedThrough, best);

            if (!outcome.applied()) {
                run.discarded();
                return;
            }

            if (outcome.offerId() == null) {
                run.nothing();
                return;
            }

            run.offered();
            pushNotificationService.push(userId, new PushEvent.InterestOffered(outcome.offerId()));
        } catch (RuntimeException exception) {
            log.warn("Interest discovery failed for user {} ({})", userId, exception.getClass().getSimpleName());
            log.debug("Interest discovery failure for user {}", userId, exception);

            run.failed();

            try {
                interestScanService.recordFailure(userId);
            } catch (RuntimeException recordException) {
                log.warn("Could not record the discovery failure for user {} ({})", userId, recordException.getClass().getSimpleName());
            }
        }
    }

    private Set<String> blocked(UUID userId) {
        Set<String> blocked = new TreeSet<>(interestAgentRepository.findTopicKeys(userId));
        ZonedDateTime declinedAfter = ZonedDateTime.now(clock).minus(interestSettings.declineCooldown());

        blocked.addAll(interestOfferRepository.findBlockedTopicKeys(userId, declinedAfter));

        return blocked;
    }

    /**
     * Each memory is one line, its text quoted with every line break escaped, so nothing a memory says
     * can start a line of its own. Topic keys are already reduced to {@code [a-z0-9.-]}.
     */
    static String render(List<UserMemory> memories, Set<String> blocked) {
        StringBuilder rendered = new StringBuilder("Memories:\n");

        for (UserMemory memory : memories) {
            rendered.append("- id=").append(memory.getId())
                    .append(" kind=").append(memory.getKind())
                    .append(": ").append(PromptQuoting.quote(memory.getContent()))
                    .append('\n');
        }

        rendered.append("\nExcluded topics:\n");

        if (blocked.isEmpty()) {
            rendered.append("(none)\n");
        }

        for (String topicKey : blocked) {
            rendered.append("- ").append(topicKey).append('\n');
        }

        return rendered.toString();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
