package com.solesonic.tools.jira;

import com.solesonic.model.atlassian.jira.issue.User;
import com.solesonic.service.atlassian.JiraService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import static com.solesonic.tools.jira.CreateJiraTools.CREATE_JIRA_ISSUE;

@Component
public class AssigneeJiraTools {
    private static final Logger log = LoggerFactory.getLogger(AssigneeJiraTools.class);

    public static final String ASSIGN_JIRA = "assignee_id_lookup";

    private final JiraService jiraService;

    public AssigneeJiraTools(JiraService jiraService) {
        this.jiraService = jiraService;
    }

    public record AssigneeResponse(String assigneeId) {}
    public record AssigneeRequest(String assignee) {}

    public AssigneeResponse assigneeLookup(@ToolParam(description = "Assignee to look up.") AssigneeRequest assigneeRequest) {
        log.debug("Invoking user search for: {}", assigneeRequest);

        User user = jiraService.userSearch(assigneeRequest.assignee())
                .stream()
                .findFirst()
                .orElse(User.accountId(null).build());

        log.debug("Found user with ID: {}", user.accountId());

        return new AssigneeResponse(user.accountId());
    }
}
