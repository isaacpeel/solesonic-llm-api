package com.solesonic.model.atlassian.jira.issue;

import java.util.List;

public record Content(
        String type,
        List<TextContent> content
) {

    public static Builder content(List<TextContent> content) {
        return new Builder().content(content);
    }

    public static class Builder {
        private String type;
        private List<TextContent> content;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder content(List<TextContent> content) {
            this.content = content;
            return this;
        }

        public Content build() {
            return new Content(type, content);
        }
    }

}

