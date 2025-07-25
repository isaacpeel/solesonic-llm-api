package com.solesonic.izzybot.exception;

import com.solesonic.izzybot.model.atlassian.jira.issue.JiraIssue;

public class DuplicateJiraCreationException extends RuntimeException {
    private JiraIssue jiraIssue;

    public DuplicateJiraCreationException(String message) {
        super(message);
    }

    public DuplicateJiraCreationException(JiraIssue jiraIssue) {
        this.jiraIssue = jiraIssue;
    }

    public JiraIssue getJiraIssue() {
        return jiraIssue;
    }
}
