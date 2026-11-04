package com.solesonic.service.etl;

import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.model.chat.attachment.ChatAttachmentIndexState;
import com.solesonic.model.chat.attachment.ExtractionFailureReason;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.llm.UserLlmKeyService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Re-indexes a conversation's documents that are not retrievable, in the background, when the
 * conversation is used.
 * <p>
 * Exists for the documents {@code V3_27} orphaned — and for anything that breaks the same way later:
 * a document whose ingest crashed, or failed while the embedding model was down. It runs off the
 * request path because embedding a large document, with keyword enrichment, can take minutes; the
 * turn that triggers it answers without the document, and the next one has it.
 * <p>
 * Bounded on every axis, because this is production and a burst of old conversations must not flood
 * the embedding endpoint: at most one run per chat per {@code interval}, at most {@code max-per-run}
 * documents per run, on a small dedicated pool with a bounded queue that rejects (and logs) rather
 * than blocks. Never the request thread, never {@code boundedElastic}, which turns share.
 * <p>
 * Never re-indexes a detached attachment (promoted or removed), one leased by another worker, one
 * sent this turn, or one that failed for a reason retrying cannot fix. Emits no events: the stream
 * of the turn that scheduled it may be closed, and the UI sees the new state on its next fetch.
 * Disabled by default ({@code solesonic.llm.attachment.heal.enabled}).
 */
@Service
public class ChatDocumentHealer {
    private static final Logger log = LoggerFactory.getLogger(ChatDocumentHealer.class);

    private static final int THREADS = 2;
    private static final int QUEUE_CAPACITY = 50;
    private static final int THROTTLE_PRUNE_THRESHOLD = 1_000;
    private static final int MAX_PENDING_PER_USER = 2;

    /**
     * Failures retrying cannot fix. A user re-sending the attachment still retries it, as before;
     * only the background never does.
     */
    private static final Set<ExtractionFailureReason> PERMANENT = Set.of(
            ExtractionFailureReason.DOCUMENT_TOO_LARGE,
            ExtractionFailureReason.DOCUMENT_UNREADABLE,
            ExtractionFailureReason.EXCEEDED_DOCUMENT_LIMIT);

    private final ChatAttachmentService chatAttachmentService;
    private final ChatDocumentIngestionService chatDocumentIngestionService;
    private final UserLlmKeyService userLlmKeyService;
    private final boolean enabled;
    private final Duration interval;
    private final int maxPerRun;
    private final Duration retryCooldown;
    private final Duration staleLease;
    private final Executor executor;
    private final Clock clock;

    /**
     * When each chat last had a run scheduled. In memory: a restart only means one extra run.
     */
    private final Map<UUID, Instant> lastScheduled = new ConcurrentHashMap<>();

    /**
     * Runs queued or running per user. Entries stay at zero once a user's runs finish; there is one
     * per user who has used a chat since startup, which is small.
     */
    private final Map<UUID, AtomicInteger> pendingByUser = new ConcurrentHashMap<>();

    @Autowired
    public ChatDocumentHealer(ChatAttachmentService chatAttachmentService,
                              ChatDocumentIngestionService chatDocumentIngestionService,
                              UserLlmKeyService userLlmKeyService,
                              @Value("${solesonic.llm.attachment.heal.enabled:false}") boolean enabled,
                              @Value("${solesonic.llm.attachment.heal.interval:PT10M}") Duration interval,
                              @Value("${solesonic.llm.attachment.heal.max-per-run:2}") int maxPerRun,
                              @Value("${solesonic.llm.attachment.heal.retry-cooldown:PT1H}") Duration retryCooldown,
                              @Value("${solesonic.llm.attachment.index.stale-lease:PT15M}") Duration staleLease) {
        this(chatAttachmentService, chatDocumentIngestionService, userLlmKeyService, enabled, interval, maxPerRun,
                retryCooldown, staleLease, healerPool(), Clock.systemUTC());
    }

    ChatDocumentHealer(ChatAttachmentService chatAttachmentService,
                       ChatDocumentIngestionService chatDocumentIngestionService,
                       UserLlmKeyService userLlmKeyService,
                       boolean enabled,
                       Duration interval,
                       int maxPerRun,
                       Duration retryCooldown,
                       Duration staleLease,
                       Executor executor,
                       Clock clock) {
        this.chatAttachmentService = chatAttachmentService;
        this.chatDocumentIngestionService = chatDocumentIngestionService;
        this.userLlmKeyService = userLlmKeyService;
        this.enabled = enabled;
        this.interval = interval;
        this.maxPerRun = maxPerRun;
        this.retryCooldown = retryCooldown;
        this.staleLease = staleLease;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * A full queue throws {@link RejectedExecutionException} (the default abort policy) rather than
     * silently discarding, so {@link #schedule} can release the throttle and log it.
     */
    private static ExecutorService healerPool() {
        AtomicInteger threadNumber = new AtomicInteger();

        return new ThreadPoolExecutor(THREADS, THREADS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "chat-document-healer-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    /**
     * Schedules a heal run for one conversation, unless one ran within the interval. Returns at
     * once; never throws.
     *
     * @param sentThisTurn the attachment ids of the current send, already handled by the send path
     */
    public void schedule(UUID chatId, UUID userId, Set<UUID> sentThisTurn) {
        if (!enabled || chatId == null || userId == null) {
            return;
        }

        try {
            Instant now = clock.instant();

            if (!claimSlot(chatId, now)) {
                return;
            }

            AtomicInteger userPending = pendingByUser.computeIfAbsent(userId, _ -> new AtomicInteger());

            if (userPending.incrementAndGet() > MAX_PENDING_PER_USER) {
                // One user with many chats must not fill the shared queue and starve everyone else.
                userPending.decrementAndGet();
                lastScheduled.remove(chatId, now);

                log.debug("User {} already has {} heal run(s) pending; not healing chat {} this turn",
                        userId, MAX_PENDING_PER_USER, chatId);
                return;
            }

            Set<UUID> excluded = sentThisTurn == null ? Set.of() : Set.copyOf(sentThisTurn);

            try {
                executor.execute(() -> {
                    try {
                        run(chatId, userId, excluded, now);
                    } finally {
                        userPending.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException rejectedExecutionException) {
                log.warn("Healer queue is full; not healing chat {} this turn", chatId);

                userPending.decrementAndGet();
                lastScheduled.remove(chatId, now);
            }
        } catch (RuntimeException runtimeException) {
            log.warn("Could not schedule healing for chat {}: {}", chatId, runtimeException.getMessage());
        }
    }

    /**
     * Takes this chat's slot for the interval, atomically, so two turns arriving together schedule
     * one run between them.
     */
    private boolean claimSlot(UUID chatId, Instant now) {
        if (lastScheduled.size() > THROTTLE_PRUNE_THRESHOLD) {
            lastScheduled.values().removeIf(scheduled -> scheduled.isBefore(now.minus(interval)));
        }

        Instant[] previous = new Instant[1];

        Instant current = lastScheduled.compute(chatId, (_, scheduled) -> {
            previous[0] = scheduled;

            return scheduled != null && scheduled.isAfter(now.minus(interval)) ? scheduled : now;
        });

        return current.equals(now) && !now.equals(previous[0]);
    }

    private void run(UUID chatId, UUID userId, Set<UUID> excluded, Instant scheduledAt) {
        try {
            userLlmKeyService.runAsUser(userId, () -> healChat(chatId, userId, excluded));
        } catch (LlmKeyMissingException llmKeyMissingException) {
            log.debug("User {} has no model key; not healing chat {}", userId, chatId);

            lastScheduled.remove(chatId, scheduledAt);
        } catch (RuntimeException runtimeException) {
            log.warn("Healing chat {} failed: {}", chatId, runtimeException.getMessage());
        }
    }

    private void healChat(UUID chatId, UUID userId, Set<UUID> excluded) {
        List<ChatAttachmentIndexState> indexStates = chatAttachmentService.documentIndexStates(chatId);

        ZonedDateTime now = ZonedDateTime.now(clock);
        int healed = 0;

        for (ChatAttachmentIndexState indexState : indexStates) {
            if (healed >= maxPerRun) {
                log.info("Healed {} document(s) of chat {}; leaving the rest for a later run", healed, chatId);
                break;
            }

            if (!eligible(indexState, userId, excluded, now)) {
                continue;
            }

            try {
                ChatDocumentIngestionService.Health health = chatDocumentIngestionService.health(indexState);

                if (health != ChatDocumentIngestionService.Health.UNINDEXED
                        && health != ChatDocumentIngestionService.Health.BROKEN) {
                    continue;
                }

                log.info("Healing {} attachment {} of chat {}", health, indexState.id(), chatId);

                healed++;

                boolean retrievable = chatDocumentIngestionService.heal(chatId, userId, indexState.id());

                log.info("Healing attachment {} of chat {} {}", indexState.id(), chatId,
                        retrievable ? "succeeded" : "did not make it retrievable");
            } catch (RuntimeException runtimeException) {
                log.warn("Healing attachment {} of chat {} failed: {}",
                        indexState.id(), chatId, runtimeException.getMessage());
            }
        }
    }

    /**
     * What may be looked at at all, before any query is spent on it (plan section 5).
     */
    private boolean eligible(ChatAttachmentIndexState indexState,
                             UUID userId,
                             Set<UUID> excluded,
                             ZonedDateTime now) {
        if (excluded.contains(indexState.id())
                || !userId.equals(indexState.userId())
                || indexState.indexDetached()
                || indexState.leased(now.minus(staleLease))) {
            return false;
        }

        if (indexState.chunkCount() != null) {
            // Claims to be indexed: the health check decides.
            return true;
        }

        ExtractionFailureReason failureReason = indexState.extractionFailureReason();

        if (failureReason == null) {
            return true;
        }

        if (PERMANENT.contains(failureReason)) {
            return false;
        }

        return indexState.indexAttemptedAt() == null
                || indexState.indexAttemptedAt().isBefore(now.minus(retryCooldown));
    }
}
