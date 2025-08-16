package com.solesonic.model.atlassian.jira.issue;

public record Watches(
        String self,
        int watchCount,
        boolean isWatching
) {}

