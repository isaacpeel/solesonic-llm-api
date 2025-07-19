package com.solesonic.izzybot.model.atlassian.jira.issue;

public record NonEditableReason(
        String reason,
        String message
) {}
