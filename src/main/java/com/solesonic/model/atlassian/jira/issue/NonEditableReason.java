package com.solesonic.model.atlassian.jira.issue;

public record NonEditableReason(
        String reason,
        String message
) {}
