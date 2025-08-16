package com.solesonic.model.atlassian.jira.issue;

import java.util.Map;

public record IssueRestriction(
        Map<String, Object> issuerestrictions,
        boolean shouldDisplay
) {}
