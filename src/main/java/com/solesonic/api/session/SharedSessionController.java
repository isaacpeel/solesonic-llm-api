package com.solesonic.api.session;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.session.SessionMessageAccepted;
import com.solesonic.model.session.SessionMessageRequest;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SharedSession;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.redis.RedisStreamingChatService;
import com.solesonic.service.session.SessionMessageService;
import com.solesonic.service.session.SessionStreamService;
import com.solesonic.service.session.SharedSessionService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.api.chat.StreamingChatController.LAST_EVENT_ID;

/**
 * A session several users share with one A2A agent, as any participant reaches it. Every endpoint
 * checks the caller takes part, or took part, inside the query, so another user's session is a
 * {@code 404}; only a participant still seated may send, cancel or leave.
 * <p>
 * Sending answers {@code 202} once its run has started rather than streaming it: every participant
 * reads every run, the sender's own included, from {@code /stream}, so there is one way frames arrive.
 */
@RestController
@RequestMapping("/shared-sessions")
public class SharedSessionController {
    private static final Logger log = LoggerFactory.getLogger(SharedSessionController.class);

    private static final Set<SessionStatus> OPEN_STATUSES = EnumSet.of(SessionStatus.ACTIVE, SessionStatus.COMPLETED);
    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(5);

    private final SharedSessionService sharedSessionService;
    private final SessionMessageService sessionMessageService;
    private final SessionStreamService sessionStreamService;
    private final RedisStreamingChatService redisStreamingChatService;
    private final UserRequestContext userRequestContext;

    public SharedSessionController(SharedSessionService sharedSessionService,
                                   SessionMessageService sessionMessageService,
                                   SessionStreamService sessionStreamService,
                                   RedisStreamingChatService redisStreamingChatService,
                                   UserRequestContext userRequestContext) {
        this.sharedSessionService = sharedSessionService;
        this.sessionMessageService = sessionMessageService;
        this.sessionStreamService = sessionStreamService;
        this.redisStreamingChatService = redisStreamingChatService;
        this.userRequestContext = userRequestContext;
    }

    /**
     * Without {@code status}, the sessions still accepting messages: active ones and completed ones that
     * can be started again.
     */
    @GetMapping
    public ResponseEntity<Page<SharedSessionSummary>> list(@RequestParam(required = false) Set<SessionStatus> status, Pageable pageable) {
        Set<SessionStatus> statuses = status == null || status.isEmpty() ? OPEN_STATUSES : status;

        return ResponseEntity.ok(sharedSessionService.list(userRequestContext.getUserId(), statuses, pageable));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SharedSessionSummary> get(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(sharedSessionService.summary(sessionId, userRequestContext.getUserId()));
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> messages(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(sharedSessionService.messages(sessionId, userRequestContext.getUserId()));
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<SessionMessageAccepted> send(@PathVariable UUID sessionId,
                                                       @RequestBody SessionMessageRequest sessionMessageRequest,
                                                       Authentication authentication) {
        log.info("Message in shared session {}", sessionId);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sessionMessageService.send(sessionId, userRequestContext.getUserId(), sessionMessageRequest, authentication));
    }

    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<?>>> stream(@PathVariable UUID sessionId,
                                                           @RequestHeader(value = LAST_EVENT_ID, required = false) String lastEventIdHeader,
                                                           @RequestParam(value = "lastEventId", required = false) String lastEventIdParameter) {
        String lastEventId = StringUtils.isNotEmpty(lastEventIdHeader) ? lastEventIdHeader : lastEventIdParameter;

        return sessionStreamService.watch(sessionId, userRequestContext.getUserId(), lastEventId);
    }

    /**
     * The same signal a chat's stop sends. Any seated participant may stop a run: they all watch it.
     */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID sessionId) {
        SharedSession sharedSession = sharedSessionService.requireSeated(sessionId, userRequestContext.getUserId());
        UUID streamUserId = SharedSessionService.streamOwner(sharedSessionService.participants(sessionId));

        log.info("Cancelling the run in shared session {}", sessionId);

        RedisStreamingChatService.CancelOutcome outcome = redisStreamingChatService.cancel(sharedSession.getChatId(), streamUserId)
                .block(CANCEL_TIMEOUT);

        return outcome == RedisStreamingChatService.CancelOutcome.CANCEL_REQUESTED
                ? ResponseEntity.accepted().build()
                : ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> leave(@PathVariable UUID sessionId) {
        log.info("Leaving shared session {}", sessionId);

        sharedSessionService.leave(sessionId, userRequestContext.getUserId());

        return ResponseEntity.noContent().build();
    }
}
