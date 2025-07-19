package com.solesonic.izzybot.tools.jira;

import com.solesonic.izzybot.model.atlassian.jira.issue.*;
import com.solesonic.izzybot.service.atlassian.JiraService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.solesonic.izzybot.service.atlassian.JiraService.ISSUE_TYPE_ID;
import static com.solesonic.izzybot.service.atlassian.JiraService.PROJECT_ID;
import static com.solesonic.izzybot.tools.jira.AssigneeJiraTools.ASSIGN_JIRA;

@Component
public class CreateJiraTools {
    private static final Logger log = LoggerFactory.getLogger(CreateJiraTools.class);

    public static final String CREATE_JIRA_ISSUE = "create_jira_issue";
    public static final String JIRA_URL_TEMPLATE = "https://solesonic-llm-api.atlassian.net/browse/{issueId}";

    private final JiraService jiraService;

    public CreateJiraTools(JiraService jiraService) {
        this.jiraService = jiraService;
    }

    public record CreateJiraResponse(String issueId, String issueUri) {}
    public record CreateJiraRequest(String summary, String description, List<String> acceptanceCriteria, String assigneeId) {}

    @SuppressWarnings("unused")
    @Tool(name = CREATE_JIRA_ISSUE,
            description = "Creates a jira issue.  Use responsibly and ensure no repeated calls for the same request.  If an assignee is needed always call '"+ASSIGN_JIRA+"' first.")
    public CreateJiraResponse createJiraIssue(@ToolParam(description = "Request to create a jira issue.") CreateJiraRequest createJiraRequest) {
        log.debug("Invoking create jira function");
        log.debug("Summary: {}", createJiraRequest.summary);
        log.debug("Description: {}", createJiraRequest.description);
        log.debug("Assignee ID: {}", createJiraRequest.assigneeId);

        TextContent descriptionText = TextContent.text(createJiraRequest.description())
                .type("text")
                .build();

        List<TextContent> acceptanceCriteria = new ArrayList<>();

        createJiraRequest.acceptanceCriteria().forEach(ac -> {
            TextContent acceptanceCriteriaItemTextContent = TextContent.type("text")
                    .text(ac)
                    .build();

            TextContent acceptanceCriteriaItemContent = TextContent.type("paragraph")
                    .content(List.of(acceptanceCriteriaItemTextContent))
                    .build();

            TextContent listItemContent = TextContent.type("listItem")
                    .content(List.of(acceptanceCriteriaItemContent))
                    .build();

            acceptanceCriteria.add(listItemContent);
        });

        Content bulletList = Content
                .content(acceptanceCriteria)
                .type("bulletList")
                .build();

        Content descriptionContent = Content.content(List.of(descriptionText))
                .type("paragraph")
                .build();

        TextContent acceptanceCriteriaHeader = TextContent.text("Acceptance Criteria:")
                .type("text")
                .build();

        Content acceptanceCriteriaContent = Content
                .content(List.of(acceptanceCriteriaHeader))
                .type("paragraph")
                .build();

        Description description = Description.content(List.of(descriptionContent, acceptanceCriteriaContent, bulletList))
                .type("doc")
                .version(1)
                .build();

        IssueType issueType = IssueType.id(ISSUE_TYPE_ID).build();

        Project project = Project.id(PROJECT_ID).build();


        User user = User.accountId(createJiraRequest.assigneeId()).build();

        Fields fields = Fields.summary(createJiraRequest.summary())
                .project(project)
                .description(description)
                .issuetype(issueType)
                .assignee(user)
                .build();

        JiraIssue jiraIssue = JiraIssue.fields(fields).build();

        JiraIssue created = jiraService.create(jiraIssue);

        log.debug("Created jira issue: {}", created);

        String jiraUri = JIRA_URL_TEMPLATE.replace("{issueId}", created.key());

        log.debug("Using jira uri: {}", jiraUri);

        return new CreateJiraResponse(created.id(), jiraUri);
    }
}
