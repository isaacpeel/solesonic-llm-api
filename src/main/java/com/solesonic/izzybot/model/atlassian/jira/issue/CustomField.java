package com.solesonic.izzybot.model.atlassian.jira.issue;

public record CustomField(
        boolean hasEpicLinkFieldDependency,
        boolean showField,
        NonEditableReason nonEditableReason
) {}

