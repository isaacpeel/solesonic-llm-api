package com.solesonic.api.session;

import com.solesonic.model.session.SessionInviteRequest;
import com.solesonic.model.session.SessionInviteSummary;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.session.SessionInviteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Invites to a shared session. The caller is always the token's subject: there is no {@code userId} in
 * any path to disagree with it, and an invite between two other users is a {@code 404}.
 * {@code /shared-sessions/invites} is a literal segment, which Spring ranks above
 * {@code /shared-sessions/{sessionId}}.
 */
@RestController
@RequestMapping("/shared-sessions/invites")
public class SessionInviteController {
    private static final Logger log = LoggerFactory.getLogger(SessionInviteController.class);

    static final String INCOMING = "incoming";
    static final String OUTGOING = "outgoing";

    private final SessionInviteService sessionInviteService;
    private final UserRequestContext userRequestContext;

    public SessionInviteController(SessionInviteService sessionInviteService, UserRequestContext userRequestContext) {
        this.sessionInviteService = sessionInviteService;
        this.userRequestContext = userRequestContext;
    }

    @PostMapping
    public ResponseEntity<SessionInviteSummary> invite(@RequestBody SessionInviteRequest sessionInviteRequest) {
        log.info("Sending a shared-session invite");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionInviteService.invite(userRequestContext.getUserId(), sessionInviteRequest));
    }

    @GetMapping
    public ResponseEntity<List<SessionInviteSummary>> list(@RequestParam(defaultValue = INCOMING) String direction) {
        UUID userId = userRequestContext.getUserId();

        if (OUTGOING.equalsIgnoreCase(direction)) {
            return ResponseEntity.ok(sessionInviteService.outgoing(userId));
        }

        if (!INCOMING.equalsIgnoreCase(direction)) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(sessionInviteService.incoming(userId));
    }

    @GetMapping("/{inviteId}")
    public ResponseEntity<SessionInviteSummary> get(@PathVariable UUID inviteId) {
        return ResponseEntity.ok(sessionInviteService.get(inviteId, userRequestContext.getUserId()));
    }

    @PostMapping("/{inviteId}/accept")
    public ResponseEntity<SharedSessionSummary> accept(@PathVariable UUID inviteId, Authentication authentication) {
        log.info("Accepting shared-session invite {}", inviteId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionInviteService.accept(inviteId, userRequestContext.getUserId(), authentication));
    }

    @PostMapping("/{inviteId}/decline")
    public ResponseEntity<Void> decline(@PathVariable UUID inviteId) {
        log.info("Declining shared-session invite {}", inviteId);

        sessionInviteService.decline(inviteId, userRequestContext.getUserId());

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{inviteId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID inviteId) {
        log.info("Cancelling shared-session invite {}", inviteId);

        sessionInviteService.cancel(inviteId, userRequestContext.getUserId());

        return ResponseEntity.noContent().build();
    }
}
