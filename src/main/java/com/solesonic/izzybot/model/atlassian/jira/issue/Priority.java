package com.solesonic.izzybot.model.atlassian.jira.issue;

public record Priority(
        String self,
        String iconUrl,
        String name,
        String id
) {}
