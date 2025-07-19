package com.solesonic.izzybot.model.atlassian.jira.issue;

public record Status(
        String self,
        String description,
        String iconUrl,
        String name,
        String id,
        StatusCategory statusCategory
) {}
