package com.solesonic.izzybot.model.atlassian.jira.issue;

import java.util.List;

public record Description(
        String type,
        Integer version,
        List<Content> content
) {

    public static Builder content(List<Content> content) {
        return new Builder().content(content);
    }

    public static class Builder {
        private String type;
        private Integer version;
        private List<Content> content;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder version(Integer version) {
            this.version = version;
            return this;
        }

        public Builder content(List<Content> content) {
            this.content = content;
            return this;
        }

        public Description build() {
            return new Description(type, version, content);
        }
    }
}
