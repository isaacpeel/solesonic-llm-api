package com.solesonic.service.intent;

import org.apache.commons.lang3.StringUtils;

public enum IntentType {
    CREATING_JIRA_ISSUE("creating a jira issue"),
    CREATING_CONFLUENCE_PAGE("creating a confluence page"),
    JIRA_AGILE("jira agile"),
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
                 "creating_jira_issue"
                    -> CREATING_JIRA_ISSUE;
            case "creating a confluence page",
                 "confluence",
                 "confluence page",
                 "create_confluence_page" ->
                    CREATING_CONFLUENCE_PAGE;
            case "jira agile requests",
                 "jira_agile"-> JIRA_AGILE;
            default -> GENERAL;
        };
    }

}
