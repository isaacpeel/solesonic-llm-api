package com.solesonic.service.intent;

import org.apache.commons.lang3.StringUtils;

public enum IntentType {
    CREATING_JIRA_ISSUE("creating a jira issue"),
    CREATING_CONFLUENCE_PAGE("creating a confluence page"),
    GENERAL("general");

    private final String label;

    IntentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static IntentType fromLabel(String value) {
        if (StringUtils.isBlank(value)) {
            return GENERAL;
        }
        String trimmedValue = value.trim().toLowerCase();

        return switch (trimmedValue) {
            case "creating a jira issue",
                 "jira", "jira issue",
                 "create jira issue"
                    -> CREATING_JIRA_ISSUE;
            case "creating a confluence page",
                 "confluence",
                 "confluence page",
                 "create confluence page" ->
                    CREATING_CONFLUENCE_PAGE;
            default -> GENERAL;
        };
    }

}
