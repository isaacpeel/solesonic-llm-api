package com.solesonic.model.atlassian.jira.issue;

@SuppressWarnings("unused")
public record Watchers(
        String self,
        int watchCount,
        boolean isWatching
) {}
