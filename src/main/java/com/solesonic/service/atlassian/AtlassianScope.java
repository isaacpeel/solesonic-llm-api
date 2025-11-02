package com.solesonic.service.atlassian;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public class AtlassianScope {

    public static final List<String> SCOPES = List.of(
            //Granular scopes
            "read:issue:jira",
            "write:issue:jira",
            "read:issue:jira-software",
            "delete:issue:jira",
            "read:avatar:jira",
            "write:issue:jira-software",
            "read:issue-meta:jira",
            "read:field-configuration:jira",
            "read:issue-security-level:jira",
            "read:issue.changelog:jira",
            "read:issue.vote:jira",
            "read:user:jira",
            "read:status:jira",
            "read:application-role:jira",
            "read:group:jira",
            "write:page:confluence",
            "read:page:confluence",
            "delete:page:confluence",
            "read:space:confluence",
            "write:space:confluence",
            "read:space-details:confluence",
            "delete:jira:issue",
            "delete:jira-work",
            "read:board-scope:jira-software",
            "read:issue-details:jira",
            "read:issue-type:jira",
            "read:issue-status:jira",
            "read:task:jira-service-management",

            //Special
            "READ",
            "WRITE",
            "offline_access",

            //Classic Scopes
            "read:jira-work",
            "manage:jira-project",
            "manage:jira-configuration",
            "read:jira-user",
            "write:jira-work",
            "manage:jira-webhook",
            "manage:jira-data-provider",
            "read:servicedesk-request",
            "manage:servicedesk-customer",
            "write:servicedesk-request",
            "read:servicemanagement-insight-objects");

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
