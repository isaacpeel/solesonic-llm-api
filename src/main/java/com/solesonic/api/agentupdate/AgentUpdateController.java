package com.solesonic.api.agentupdate;

import com.solesonic.model.agentupdate.AgentUpdateRequest;
import com.solesonic.model.agentupdate.AgentUpdateResponse;
import com.solesonic.service.agentupdate.AgentUpdateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service to service: an A2A agent's own Keycloak client (client credentials, role
 * {@code agent-notifier}) delivers a chat to a user. Never called by a user's token.
 */
@RestController
public class AgentUpdateController {
    public static final String PATH = "/agent-updates";

    private final AgentUpdateService agentUpdateService;

    public AgentUpdateController(AgentUpdateService agentUpdateService) {
        this.agentUpdateService = agentUpdateService;
    }

    @PostMapping(PATH)
    @PreAuthorize("hasRole('agent-notifier')")
    public ResponseEntity<AgentUpdateResponse> deliver(@RequestBody AgentUpdateRequest request) {
        AgentUpdateResponse response = agentUpdateService.deliver(request);

        return ResponseEntity.status(response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }
}
