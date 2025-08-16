package com.solesonic.izzybot.api.atlassian;

import com.solesonic.izzybot.model.atlassian.auth.AtlassianAuthLinkResponse;
import com.solesonic.izzybot.service.atlassian.JiraAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/atlassian")
public class JiraAuthController {
    private final JiraAuthService jiraAuthService;

    public JiraAuthController(JiraAuthService jiraAuthService) {
        this.jiraAuthService = jiraAuthService;
    }

    @GetMapping("/auth/uri")
    public ResponseEntity<AtlassianAuthLinkResponse> getJiraAuthUri() {
        String authUri = jiraAuthService.authUri();
        AtlassianAuthLinkResponse atlassianAuthLinkResponse = new AtlassianAuthLinkResponse(authUri);
        return ResponseEntity.ok(atlassianAuthLinkResponse);
    }

    @GetMapping("/auth/callback")
    public ResponseEntity<Void> jiraCallback(@RequestParam String code) {
        jiraAuthService.callback(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/accessible-resources")
    public ResponseEntity<String> accessibleResources() {
        String accessibleResources = jiraAuthService.accessibleResources();
        return ResponseEntity.ok(accessibleResources);
    }
}
