package com.solesonic.izzybot.model.atlassian.jira.issue;

public record IssueType(
        String self,
        String id,
        String description,
        String iconUrl,
        String name,
        boolean subtask,
        Integer avatarId,
        Integer hierarchyLevel
) {

    public static Builder id(String id) {
        return new Builder().id(id);
    }

    public static class Builder {
        private String id;

        public Builder id(final String id) {
            this.id = id;
            return this;
        }

        public IssueType build() {
            return new IssueType(null, id, null, null, null, false, null, null);
        }
    }
}
