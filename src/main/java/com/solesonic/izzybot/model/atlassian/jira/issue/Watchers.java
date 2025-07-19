package com.solesonic.izzybot.model.atlassian.jira.issue;

public record Watchers(
        String self,
        int watchCount,
        boolean isWatching
) {}
