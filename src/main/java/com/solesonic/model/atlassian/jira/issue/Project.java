package com.solesonic.model.atlassian.jira.issue;

public record Project(
        String self,
        String id,
        String key,
        String name,
        String projectTypeKey,
        boolean simplified,
        AvatarUrls avatarUrls
) {

    public static Builder id(final String id) {
        return new Builder().id(id);
    }

    public static class Builder {
        private String self;
        private String id;
        private String key;
        private String name;
        private String projectTypeKey;
        private boolean simplified;
        private AvatarUrls avatarUrls;

        public Builder self(final String self) {
            this.self = self;
            return this;
        }

        public Builder id(final String id) {
            this.id = id;
            return this;
        }

        public Builder key(final String key) {
            this.key = key;
            return this;
        }

        public Builder name(final String name) {
            this.name = name;
            return this;
        }

        @SuppressWarnings("unused")
        public Builder projectTypeKey(final String projectTypeKey) {
            this.projectTypeKey = projectTypeKey;
            return this;
        }

        @SuppressWarnings("unused")
        public Builder simplified(final boolean simplified) {
            this.simplified = simplified;
            return this;
        }

        @SuppressWarnings("unused")
        public Builder avatarUrls(final AvatarUrls avatarUrls) {
            this.avatarUrls = avatarUrls;
            return this;
        }

        public Project build() {
            return new Project(self, id, key, name, projectTypeKey, simplified, avatarUrls);
        }
    }
}
