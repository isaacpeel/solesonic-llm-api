package com.solesonic.service.agentupdate;

import com.solesonic.exception.agentupdate.AgentUpdateException;
import com.solesonic.model.agentupdate.AgentUpdate;
import com.solesonic.model.agentupdate.AgentUpdateRequest;
import com.solesonic.model.agentupdate.AgentUpdateResponse;
import com.solesonic.model.push.PushEvent;
import com.solesonic.repository.agentupdate.AgentUpdateRepository;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.user.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Backs {@code POST /agent-updates}: an A2A agent (the jobs agent) writes one chat holding one
 * {@code ASSISTANT} message into a user's group and has it pushed, the way a news interest does.
 * <p>
 * The caller is a service client, not the user, so the owner comes from the body: an allowlisted
 * agent, a user who has signed in at least once, at most {@code max-per-day} updates per user. A
 * repeated (agent, idempotency key) returns the first chat and pushes nothing. Logs name ids and
 * counts only, never the title or text.
 */
@Service
public class AgentUpdateService {
    private static final Logger log = LoggerFactory.getLogger(AgentUpdateService.class);

    public static final int MAX_MARKDOWN_BYTES = 16 * 1024;
    public static final int MAX_TITLE_CHARACTERS = 120;
    public static final int MAX_GROUP_NAME_CHARACTERS = 60;
    private static final Pattern AGENT_NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9:._-]{8,128}");
    private static final Duration RATE_WINDOW = Duration.ofDays(1);

    /**
     * The group each agent files its chats in when the update names none.
     */
    private static final Map<String, String> DEFAULT_GROUPS = Map.of("jobs", "Jobs");

    private final AgentUpdateRepository agentUpdateRepository;
    private final AgentUpdateWriter writer;
    private final UserProfileService userProfileService;
    private final PushNotificationService pushNotificationService;
    private final A2AStickyAgentService stickyAgentService;
    private final Clock clock;
    private final Set<String> allowedAgents;
    private final int maxPerDay;

    public AgentUpdateService(AgentUpdateRepository agentUpdateRepository,
                              AgentUpdateWriter writer,
                              UserProfileService userProfileService,
                              PushNotificationService pushNotificationService,
                              A2AStickyAgentService stickyAgentService,
                              Clock clock,
                              @Value("${solesonic.agent-updates.agents:jobs}") Set<String> allowedAgents,
                              @Value("${solesonic.agent-updates.max-per-day:10}") int maxPerDay) {
        this.agentUpdateRepository = agentUpdateRepository;
        this.writer = writer;
        this.userProfileService = userProfileService;
        this.pushNotificationService = pushNotificationService;
        this.stickyAgentService = stickyAgentService;
        this.clock = clock;
        this.allowedAgents = Set.copyOf(allowedAgents);
        this.maxPerDay = maxPerDay;
    }

    public AgentUpdateResponse deliver(AgentUpdateRequest request) {
        Valid valid = validate(request);

        Optional<AgentUpdate> existing = agentUpdateRepository.findByAgentAndIdempotencyKey(valid.agent(), valid.idempotencyKey());

        if (existing.isPresent()) {
            log.info("Agent update {} from {} was already delivered; not pushed again", existing.get().getId(), valid.agent());
            return duplicate(existing.get(), valid.userId());
        }

        if (userProfileService.profile(valid.userId()).isEmpty()) {
            throw AgentUpdateException.userNotFound();
        }

        ZonedDateTime now = ZonedDateTime.now(clock);

        if (agentUpdateRepository.countByUserIdAndCreatedAfter(valid.userId(), now.minus(RATE_WINDOW)) >= maxPerDay) {
            throw AgentUpdateException.rateLimited();
        }

        AgentUpdate written;

        try {
            written = writer.write(valid.userId(), valid.agent(), valid.idempotencyKey(), valid.groupName(),
                    valid.title(), valid.markdown(), now);
        } catch (DataIntegrityViolationException raced) {
            //Two deliveries of one key at once: the other one won, so this one is its duplicate.
            AgentUpdate winner = agentUpdateRepository.findByAgentAndIdempotencyKey(valid.agent(), valid.idempotencyKey())
                    .orElseThrow(() -> raced);
            return duplicate(winner, valid.userId());
        }

        log.info("Agent update {} from {} delivered as chat {}", written.getId(), valid.agent(), written.getChatId());

        if (valid.pinAgent()) {
            stickyAgentService.activate(written.getChatId(), valid.agent())
                    .doOnError(error -> log.warn("Could not attach agent {} to chat {} ({})", valid.agent(),
                            written.getChatId(), error.getClass().getSimpleName()))
                    .onErrorComplete()
                    .subscribe();
        }

        pushNotificationService.push(valid.userId(), new PushEvent.AgentUpdated(written.getChatId(), valid.agent(), written.getId()));

        return new AgentUpdateResponse(written.getChatId(), written.getId(), false);
    }

    /**
     * Another user's key is answered as if it were new to them, never with the other user's chat.
     */
    private static AgentUpdateResponse duplicate(AgentUpdate agentUpdate, UUID userId) {
        if (!agentUpdate.getUserId().equals(userId)) {
            throw AgentUpdateException.invalid("This idempotency key was already used.");
        }

        return new AgentUpdateResponse(agentUpdate.getChatId(), agentUpdate.getId(), true);
    }

    Valid validate(AgentUpdateRequest request) {
        if (request == null || request.userId() == null) {
            throw AgentUpdateException.invalid("userId is required.");
        }

        String agent = request.agent() == null ? "" : request.agent().trim();

        if (!AGENT_NAME.matcher(agent).matches()) {
            throw AgentUpdateException.invalid("agent is required.");
        }

        if (!allowedAgents.contains(agent)) {
            throw AgentUpdateException.unknownAgent();
        }

        String idempotencyKey = request.idempotencyKey() == null ? "" : request.idempotencyKey().trim();

        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw AgentUpdateException.invalid("idempotencyKey must be 8 to 128 letters, digits or : . _ -");
        }

        if (request.markdown() == null || request.markdown().getBytes(StandardCharsets.UTF_8).length > MAX_MARKDOWN_BYTES) {
            throw AgentUpdateException.invalid("markdown is required and limited to " + MAX_MARKDOWN_BYTES + " bytes.");
        }

        String markdown = AgentMarkdownSanitizer.markdown(request.markdown());
        String title = AgentMarkdownSanitizer.title(request.title(), MAX_TITLE_CHARACTERS);

        if (markdown.isBlank() || title.isBlank()) {
            throw AgentUpdateException.invalid("title and markdown must not be empty.");
        }

        String groupName = request.groupName() == null || request.groupName().isBlank()
                ? DEFAULT_GROUPS.getOrDefault(agent, agent)
                : AgentMarkdownSanitizer.title(request.groupName(), MAX_GROUP_NAME_CHARACTERS);

        if (groupName.isBlank()) {
            throw AgentUpdateException.invalid("groupName must not be empty.");
        }

        return new Valid(request.userId(), agent, title, markdown, idempotencyKey, groupName, Boolean.TRUE.equals(request.pinAgent()));
    }

    record Valid(UUID userId, String agent, String title, String markdown, String idempotencyKey, String groupName,
                 boolean pinAgent) {
    }
}
