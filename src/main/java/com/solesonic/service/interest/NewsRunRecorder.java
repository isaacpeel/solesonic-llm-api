package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.interest.AgentStatus;
import com.solesonic.model.interest.InterestAgent;
import com.solesonic.model.interest.InterestAgentItem;
import com.solesonic.model.interest.InterestUpdate;
import com.solesonic.repository.chat.ChatGroupRepository;
import com.solesonic.repository.interest.InterestAgentItemRepository;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestUpdateRepository;
import com.solesonic.service.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A news run's bookkeeping, each step its own short transaction so that no lock is held across a
 * search or a model call. Every write after the claim first re-locks the agent by its id and owner,
 * so a run whose agent was deleted meanwhile writes nothing.
 */
@Service
public class NewsRunRecorder {
    private static final Logger log = LoggerFactory.getLogger(NewsRunRecorder.class);

    private final InterestAgentRepository interestAgentRepository;
    private final InterestAgentItemRepository interestAgentItemRepository;
    private final InterestUpdateRepository interestUpdateRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final InterestChatWriter interestChatWriter;
    private final UserPreferencesService userPreferencesService;
    private final InterestSettings interestSettings;

    public NewsRunRecorder(InterestAgentRepository interestAgentRepository,
                           InterestAgentItemRepository interestAgentItemRepository,
                           InterestUpdateRepository interestUpdateRepository,
                           ChatGroupRepository chatGroupRepository,
                           InterestChatWriter interestChatWriter,
                           UserPreferencesService userPreferencesService,
                           InterestSettings interestSettings) {
        this.interestAgentRepository = interestAgentRepository;
        this.interestAgentItemRepository = interestAgentItemRepository;
        this.interestUpdateRepository = interestUpdateRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.interestChatWriter = interestChatWriter;
        this.userPreferencesService = userPreferencesService;
        this.interestSettings = interestSettings;
    }

    /**
     * Claims due agents with {@code skip locked} and moves each one's next run forward by a lease
     * before the lock is released, so a claim whose instance dies is retried after the lease rather
     * than lost, and never taken twice meanwhile.
     */
    @Transactional
    public List<InterestAgent> claim(ZonedDateTime now) {
        List<InterestAgent> claimed = interestAgentRepository.findDue(now, interestSettings.newsBatchSize());

        for (InterestAgent agent : claimed) {
            agent.setNextRunAt(lease(now));
        }

        return interestAgentRepository.saveAll(claimed);
    }

    /**
     * Renews one claimed agent's lease just before its run, since the agents of a batch run one after
     * another and a later one could otherwise outlive the lease taken at the claim. Empty when the claim
     * is no longer this run's — another instance re-claimed it, or the agent was deleted, paused or made
     * due again meanwhile — in which case the run must not happen.
     *
     * @return the agent as this run holds it, whose lease every later write checks
     */
    @Transactional
    public Optional<InterestAgent> start(InterestAgent claimed, ZonedDateTime now) {
        return locked(claimed)
                .filter(agent -> agent.getStatus() == AgentStatus.ACTIVE)
                .map(agent -> {
                    agent.setNextRunAt(lease(now));
                    return interestAgentRepository.save(agent);
                });
    }

    /**
     * A run with nothing to deliver: no new result, or none the model kept. The hashes are recorded
     * either way, so an irrelevant article is not weighed again on every run.
     */
    @Transactional
    public void quiet(InterestAgent claimed, Collection<String> urlHashes, ZonedDateTime now) {
        locked(claimed).ifPresent(agent -> {
            recordSeen(agent, urlHashes, now);
            succeeded(agent, now);
        });
    }

    /**
     * Writes the news chat, its update row and the seen hashes, and schedules the next run, in one
     * transaction. Nothing is written for an agent deleted or paused while the run was searching.
     *
     * @return the chat created, which is the caller's to push once this has committed
     */
    @Transactional
    public Optional<UUID> deliver(InterestAgent claimed,
                                  Collection<String> urlHashes,
                                  UUID updateId,
                                  String chatName,
                                  String text,
                                  int itemCount,
                                  ZonedDateTime now) {
        Optional<InterestAgent> locked = locked(claimed).filter(agent -> agent.getStatus() == AgentStatus.ACTIVE);

        if (locked.isEmpty()) {
            log.info("Agent {} was deleted or paused mid-run; discarding its update", claimed.getId());
            return Optional.empty();
        }

        InterestAgent agent = locked.get();
        UUID chatGroupId = chatGroup(agent);

        Chat chat = interestChatWriter.write(agent.getUserId(), chatName, chatGroupId, text, now);

        InterestUpdate interestUpdate = new InterestUpdate();
        interestUpdate.setId(updateId);
        interestUpdate.setUserId(agent.getUserId());
        interestUpdate.setAgentId(agent.getId());
        interestUpdate.setLabel(agent.getLabel());
        interestUpdate.setChatId(chat.getId());
        interestUpdate.setItemCount(itemCount);
        interestUpdate.setCreated(now);
        interestUpdateRepository.save(interestUpdate);

        recordSeen(agent, urlHashes, now);

        agent.setLastUpdateAt(now);
        succeeded(agent, now);

        return Optional.of(chat.getId());
    }

    /**
     * Backs off {@code 2^failures} hours, capped at the cadence, and pauses the agent after
     * {@code max-failures} in a row. An operational fault is not news: no chat and no push.
     */
    @Transactional
    public void failed(InterestAgent claimed, ZonedDateTime now) {
        locked(claimed).ifPresent(agent -> {
            int failures = agent.getFailures() + 1;

            agent.setFailures(failures);
            agent.setLastFailureAt(now);
            agent.setNextRunAt(NewsRunSchedule.backoff(now, agent.getCadence(), failures));
            agent.setUpdated(now);

            if (failures >= interestSettings.maxFailures()) {
                agent.setStatus(AgentStatus.PAUSED);
                log.info("Paused agent {} after {} failed run(s)", agent.getId(), failures);
            }

            interestAgentRepository.save(agent);
        });
    }

    @Transactional
    public int pruneItems(ZonedDateTime now) {
        return interestAgentItemRepository.deleteByFirstSeenBefore(now.minus(interestSettings.itemRetention()));
    }

    /**
     * The agent, locked, only while its lease is still the one this run holds. A lease that moved means
     * another claimer or a user's run-now owns the next run, and this one's result is discarded.
     */
    private Optional<InterestAgent> locked(InterestAgent claimed) {
        return interestAgentRepository.lockByIdAndUserId(claimed.getId(), claimed.getUserId())
                .filter(agent -> agent.getNextRunAt() != null && agent.getNextRunAt().isEqual(claimed.getNextRunAt()));
    }

    /**
     * Truncated to the microseconds Postgres stores, so the lease read back compares equal to the one
     * written.
     */
    private ZonedDateTime lease(ZonedDateTime now) {
        return now.plus(interestSettings.runLease()).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The agent's group if it still exists for this owner; otherwise the agent forgets it and the chat
     * is filed ungrouped, which is what deleting a group does to its chats anyway.
     */
    private UUID chatGroup(InterestAgent agent) {
        if (agent.getChatGroupId() == null) {
            return null;
        }

        if (chatGroupRepository.findByIdAndUserId(agent.getChatGroupId(), agent.getUserId()).isPresent()) {
            return agent.getChatGroupId();
        }

        agent.setChatGroupId(null);
        return null;
    }

    private void recordSeen(InterestAgent agent, Collection<String> urlHashes, ZonedDateTime now) {
        if (urlHashes.isEmpty()) {
            return;
        }

        List<String> alreadySeen = interestAgentItemRepository.findSeen(agent.getId(), agent.getUserId(), urlHashes);

        interestAgentItemRepository.saveAll(urlHashes.stream()
                .distinct()
                .filter(urlHash -> !alreadySeen.contains(urlHash))
                .map(urlHash -> new InterestAgentItem(agent.getId(), agent.getUserId(), urlHash, now))
                .toList());
    }

    private void succeeded(InterestAgent agent, ZonedDateTime now) {
        Duration jitter = Duration.ofMinutes(ThreadLocalRandom.current().nextLong(interestSettings.jitter().toMinutes() + 1));

        agent.setLastRunAt(now);
        agent.setFailures(0);
        agent.setNextRunAt(NewsRunSchedule.next(now, agent.getCadence(), userPreferencesService.getZone(agent.getUserId()),
                interestSettings.deliveryHour(), jitter));
        agent.setUpdated(now);

        interestAgentRepository.save(agent);
    }
}
