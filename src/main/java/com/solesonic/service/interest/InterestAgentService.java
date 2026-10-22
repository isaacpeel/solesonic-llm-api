package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.exception.interest.InterestException;
import com.solesonic.model.interest.AgentStatus;
import com.solesonic.model.interest.InterestAgent;
import com.solesonic.model.interest.InterestAgentSummary;
import com.solesonic.model.interest.InterestAgentUpdateRequest;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.service.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A user's agents, always for exactly one user: every lookup names the owner, so another user's agent
 * is indistinguishable from one that does not exist.
 */
@Service
public class InterestAgentService {
    private static final Logger log = LoggerFactory.getLogger(InterestAgentService.class);

    private final InterestAgentRepository interestAgentRepository;
    private final UserPreferencesService userPreferencesService;
    private final InterestSettings interestSettings;
    private final Clock clock;

    public InterestAgentService(InterestAgentRepository interestAgentRepository,
                                UserPreferencesService userPreferencesService,
                                InterestSettings interestSettings,
                                Clock clock) {
        this.interestAgentRepository = interestAgentRepository;
        this.userPreferencesService = userPreferencesService;
        this.interestSettings = interestSettings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<InterestAgentSummary> list(UUID userId) {
        return interestAgentRepository.findByUserIdOrderByCreatedAsc(userId)
                .stream()
                .map(InterestAgentSummary::of)
                .toList();
    }

    /**
     * The body is validated before the agent is looked for, so a bad edit is a {@code 400} rather than
     * the {@code 404} of an agent that was never fetched. Resuming clears the failure count and makes
     * the agent due at once; a changed cadence moves the next run onto it.
     */
    @Transactional
    public InterestAgentSummary update(UUID userId, UUID agentId, InterestAgentUpdateRequest request) {
        if (request == null || (request.label() == null && request.query() == null && request.cadence() == null && request.status() == null)) {
            throw InterestException.invalidAgentUpdate("Nothing to change.");
        }

        String label = request.label() == null ? null : required(request.label(), InterestText.MAX_LABEL_CHARACTERS, "label");
        String query = request.query() == null ? null : required(request.query(), InterestText.MAX_QUERY_CHARACTERS, "query");

        InterestAgent agent = owned(userId, agentId);
        ZonedDateTime now = ZonedDateTime.now(clock);

        if (label != null) {
            agent.setLabel(label);
        }

        if (query != null) {
            agent.setQuery(query);
        }

        if (request.cadence() != null && request.cadence() != agent.getCadence()) {
            agent.setCadence(request.cadence());
            reschedule(agent, now);
        }

        if (request.status() == AgentStatus.ACTIVE && agent.getStatus() == AgentStatus.PAUSED) {
            agent.setFailures(0);
            agent.setNextRunAt(now);
        }

        if (request.status() != null) {
            agent.setStatus(request.status());
        }

        agent.setUpdated(now);

        log.info("Updated agent {} for user {}", agentId, userId);

        return InterestAgentSummary.of(interestAgentRepository.save(agent));
    }

    /**
     * The agent and its seen items go; its chats, its chat group and its update rows stay, with the
     * update rows' agent nulled by the database.
     */
    @Transactional
    public void delete(UUID userId, UUID agentId) {
        InterestAgent agent = owned(userId, agentId);

        interestAgentRepository.delete(agent);

        log.info("Deleted agent {} for user {}", agentId, userId);
    }

    /**
     * Makes the agent due now; the next tick picks it up. A paused agent is not run, so it is refused
     * rather than silently accepted.
     */
    @Transactional
    public InterestAgentSummary runNow(UUID userId, UUID agentId) {
        InterestAgent agent = owned(userId, agentId);
        ZonedDateTime now = ZonedDateTime.now(clock);

        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw InterestException.invalidAgentUpdate("Resume the agent before running it.");
        }

        if (agent.getLastRunAt() != null && agent.getLastRunAt().isAfter(now.minus(interestSettings.manualRunCooldown()))) {
            throw InterestException.runTooSoon();
        }

        agent.setNextRunAt(now);
        agent.setUpdated(now);

        return InterestAgentSummary.of(interestAgentRepository.save(agent));
    }

    /**
     * Moves the next run onto the new cadence, counted from the last successful run, or to now when
     * that is already past. An agent that has never run is already due and is left alone.
     */
    private void reschedule(InterestAgent agent, ZonedDateTime now) {
        if (agent.getLastRunAt() == null) {
            return;
        }

        ZonedDateTime next = NewsRunSchedule.next(agent.getLastRunAt(), agent.getCadence(), userPreferencesService.getZone(agent.getUserId()),
                interestSettings.deliveryHour(), NewsRunSchedule.jitter(interestSettings.jitter()));

        agent.setNextRunAt(next.isBefore(now) ? now : next);
    }

    /**
     * Locked, because a news run rewrites this row's schedule and failure count under the same lock:
     * an unlocked read saved back after a run committed would put its stale columns back.
     */
    private InterestAgent owned(UUID userId, UUID agentId) {
        return interestAgentRepository.lockByIdAndUserId(agentId, userId)
                .orElseThrow(InterestException::agentNotFound);
    }

    private static String required(String text, int maxCharacters, String field) {
        String line = InterestText.singleLine(text);

        if (line == null) {
            throw InterestException.invalidAgentUpdate("The " + field + " must not be blank.");
        }

        if (line.length() > maxCharacters) {
            throw InterestException.invalidAgentUpdate("The " + field + " must be " + maxCharacters + " characters or fewer.");
        }

        return line;
    }
}
