package com.solesonic.model.atlassian.jira.issue;

public record StatusCategory(
        String self,
        int id,
        String key,
        String colorName,
        String name
) {}
