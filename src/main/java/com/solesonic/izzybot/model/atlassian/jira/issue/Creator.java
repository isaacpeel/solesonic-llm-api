package com.solesonic.izzybot.model.atlassian.jira.issue;

public record Creator(
        String self,
        String accountId,
        String emailAddress,
        AvatarUrls avatarUrls,
        String displayName,
        boolean active,
        String timeZone,
        String accountType
) {}
