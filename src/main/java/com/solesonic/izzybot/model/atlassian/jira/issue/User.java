package com.solesonic.izzybot.model.atlassian.jira.issue;

public record User(
        String self,
        String accountId,
        String emailAddress,
        AvatarUrls avatarUrls,
        String displayName,
        boolean active,
        String timeZone,
        String accountType
) {

    public static Builder accountId(String accountId) {
        return new Builder().accountId(accountId);
    }

    public static class Builder {
        private String self;
        private String accountId;
        private String emailAddress;
        private AvatarUrls avatarUrls;
        private String displayName;
        private boolean active;
        private String timeZone;
        private String accountType;

        public Builder self(final String self) {
            this.self = self;
            return this;
        }

        public Builder accountId(final String accountId) {
            this.accountId = accountId;
            return this;
        }

        public Builder emailAddress(final String emailAddress) {
            this.emailAddress = emailAddress;
            return this;
        }

        public Builder avatarUrls(final AvatarUrls avatarUrls) {
            this.avatarUrls = avatarUrls;
            return this;
        }

        public Builder displayName(final String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder active(final boolean active) {
            this.active = active;
            return this;
        }

        public Builder timeZone(final String timeZone) {
            this.timeZone = timeZone;
            return this;
        }

        public Builder accountType(final String accountType) {
            this.accountType = accountType;
            return this;
        }

        public User build() {
            return new User(self, accountId, emailAddress, avatarUrls, displayName, active, timeZone, accountType);
        }
    }
}
