package com.solesonic.izzybot.api.atlassian;

import com.solesonic.izzybot.model.atlassian.jira.issue.JiraIssue;
import com.solesonic.izzybot.model.atlassian.jira.issue.User;
import com.solesonic.izzybot.service.atlassian.JiraService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/jira")
public class JiraController {
    private final JiraService jiraService;

    JiraController(JiraService jiraService) {
        this.jiraService = jiraService;
    }

    @GetMapping("/issue/{jiraId}")
    public ResponseEntity<JiraIssue> getJiraIssue(@PathVariable String jiraId) {
        JiraIssue response = jiraService.get(jiraId);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/search/{userName}")
    public ResponseEntity<List<User>> userSearch(@PathVariable String userName) {
        List<User> users = jiraService.userSearch(userName);

        return ResponseEntity.ok(users);
    }

    @PostMapping("/issue")
    public ResponseEntity<JiraIssue> createJiraIssue(@RequestBody JiraIssue jira) {
        JiraIssue response = jiraService.create(jira);

        URI self = URI.create(response.self());

        return ResponseEntity.created(self).body(response);
    }

}
