package com.solesonic.service.atlassian;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class AtlassianScope {

    public static final List<String> SCOPES = List.of(
            //Granular scopes
            "delete:issue:jira",
            "delete:jira-work",
            "delete:jira:issue",
            "delete:page:confluence",
            "read:application-role:jira",
            "read:avatar:jira",
            "read:board-scope:jira-software",
            "read:field-configuration:jira",
            "read:group:jira",
            "read:issue-details:jira",
            "read:issue-meta:jira",
            "read:issue-security-level:jira",
            "read:issue-status:jira",
            "read:issue-type:jira",
            "read:issue.changelog:jira",
            "read:issue:jira",
            "read:issue:jira-software",
            "read:issue.vote:jira",
            "read:page:confluence",
            "read:project:jira",
            "read:space-details:confluence",
            "read:space:confluence",
            "read:status:jira",
            "read:task:jira-service-management",
            "read:user:jira",
            "write:issue:jira",
            "write:issue:jira-software",
            "write:page:confluence",
            "write:space:confluence",

            //Special
            "READ",
            "WRITE",
            "offline_access",

            //Classic Scopes
            "manage:jira-configuration",
            "manage:jira-data-provider",
            "manage:jira-project",
            "manage:jira-webhook",
            "manage:servicedesk-customer",
            "read:jira-user",
            "read:jira-work",
            "read:servicedesk-request",
            "read:servicemanagement-insight-objects",
            "write:jira-work",
            "write:servicedesk-request");

    public static String[] urlEncodedScopes() {
        int scopeSize = SCOPES.size();

        List<String> encodedScopes = new ArrayList<>(scopeSize);

        SCOPES.forEach(scope -> {
            String encode = URLEncoder.encode(scope, UTF_8);
            encodedScopes.add(encode);
        });

        return encodedScopes.toArray(new String[scopeSize]);
    }
}
