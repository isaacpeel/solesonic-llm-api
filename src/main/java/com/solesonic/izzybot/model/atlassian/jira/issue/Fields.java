package com.solesonic.izzybot.model.atlassian.jira.issue;

import java.util.List;

public record Fields(
        String statuscategorychangedate,
        IssueType issuetype,
        String timespent,
        Project project,
        String customfield_10031,
        List<String> fixVersions,
        String customfield_10034,
        String aggregatetimespent,
        String resolution,
        String customfield_10035,
        String customfield_10036,
        String customfield_10037,
        String customfield_10027,
        String customfield_10028,
        String customfield_10029,
        String resolutiondate,
        Integer workratio,
        IssueRestriction issuerestriction,
        Watches watches,
        String lastViewed,
        String created,
        String customfield_10020,
        String customfield_10021,
        String customfield_10022,
        Priority priority,
        String customfield_10023,
        String customfield_10024,
        String customfield_10025,
        String customfield_10026,
        List<String> labels,
        String customfield_10016,
        String customfield_10017,
        CustomField customfield_10018,
        String customfield_10019,
        String timeestimate,
        String aggregatetimeoriginalestimate,
        List<String> versions,
        List<String> issuelinks,
        User assignee,
        String updated,
        Status status,
        List<String> components,
        String timeoriginalestimate,
        Description description,
        String customfield_10010,
        String customfield_10014,
        TimeTracking timetracking,
        String customfield_10015,
        String customfield_10005,
        String customfield_10006,
        String security,
        String customfield_10007,
        String customfield_10008,
        String customfield_10009,
        String aggregatetimeestimate,
        List<String> attachment,
        String summary,
        User creator,
        List<String> subtasks,
        User reporter
) {

    public static Builder summary(String summary) {
        return new Builder().summary(summary);
    }

    public static class Builder {
        private String summary;
        private Description description;
        private Project project;
        private IssueType issuetype;
        private User assignee;

        // Builder methods for the selected fields
        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder description(Description description) {
            this.description = description;
            return this;
        }

        public Builder project(Project project) {
            this.project = project;
            return this;
        }

        public Builder issuetype(IssueType issuetype) {
            this.issuetype = issuetype;
            return this;
        }

        public Builder assignee(User assignee) {
            this.assignee = assignee;
            return this;
        }

        // Build method to create the Fields instance
        public Fields build() {
            return new Fields(
                    null, // statuscategorychangedate
                    issuetype,
                    null, // timespent
                    project,
                    null, // customfield_10031
                    null, // fixVersions
                    null, // customfield_10034
                    null, // aggregatetimespent
                    null, // resolution
                    null, // customfield_10035
                    null, // customfield_10036
                    null, // customfield_10037
                    null, // customfield_10027
                    null, // customfield_10028
                    null, // customfield_10029
                    null, // resolutiondate
                    null, // workratio
                    null, // issuerestriction
                    null, // watches
                    null, // lastViewed
                    null, // created
                    null, // customfield_10020
                    null, // customfield_10021
                    null, // customfield_10022
                    null, // priority
                    null, // customfield_10023
                    null, // customfield_10024
                    null, // customfield_10025
                    null, // customfield_10026
                    null, // labels
                    null, // customfield_10016
                    null, // customfield_10017
                    null, // customfield_10018
                    null, // customfield_10019
                    null, // timeestimate
                    null, // aggregatetimeoriginalestimate
                    null, // versions
                    null, // issuelinks
                    assignee, // assignee
                    null, // updated
                    null, // status
                    null, // components
                    null, // timeoriginalestimate
                    description,
                    null, // customfield_10010
                    null, // customfield_10014
                    null, // timetracking
                    null, // customfield_10015
                    null, // customfield_10005
                    null, // customfield_10006
                    null, // security
                    null, // customfield_10007
                    null, // customfield_10008
                    null, // customfield_10009
                    null, // aggregatetimeestimate
                    null, // attachment
                    summary,
                    null, // creator
                    null, // subtasks
                    null  // reporter
            );
        }
    }
    
}
