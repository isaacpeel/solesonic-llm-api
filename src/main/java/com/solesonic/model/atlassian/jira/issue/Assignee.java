package com.solesonic.model.atlassian.jira.issue;

@SuppressWarnings("unused")
public record Assignee(
        String self,
        String accountId,
        String emailAddress,
        AvatarUrls avatarUrls,
        String displayName,
        boolean active,
        String timeZone,
        String accountType
) {}
