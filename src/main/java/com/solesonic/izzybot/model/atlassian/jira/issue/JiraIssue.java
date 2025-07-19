package com.solesonic.izzybot.model.atlassian.jira.issue;

public record JiraIssue(
        String expand,
        String id,
        String self,
        String key,
        Fields fields
) {

    public static Builder fields(Fields fields) {
        return new Builder().fields(fields);
    }

    public static class Builder {
        private String expand;
        private String id;
        private String self;
        private String key;
        private Fields fields;

        public Builder expand(String expand) {
            this.expand = expand;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder self(String self) {
            this.self = self;
            return this;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder fields(Fields fields) {
            this.fields = fields;
            return this;
        }

        public JiraIssue build() {
            return new JiraIssue(expand, id, self, key, fields);
        }
    }
}
