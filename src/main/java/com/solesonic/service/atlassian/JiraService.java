package com.solesonic.service.atlassian;

import com.solesonic.exception.DuplicateJiraCreationException;
import com.solesonic.model.atlassian.jira.issue.JiraIssue;
import com.solesonic.model.atlassian.jira.issue.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static com.solesonic.config.atlassian.AtlassianConstants.ATLASSIAN_API_WEB_CLIENT;

@Service
public class JiraService {
    private static final Logger log = LoggerFactory.getLogger(JiraService.class);
    public static final String USER_PATH = "user";
    public static final String ASSIGNABLE_PATH = "assignable";
    public static final String SEARCH_PATH = "search";
    public static final String QUERY_PARAM = "query";
    public static final String PROJECT_PARAM = "project";
    public static final String PROJECT_ID = "10000";
    public static final String ISSUE_TYPE_ID = "10001";

    @Value("${solesonic.llm.jira.cloud.id.path}")
    private String cloudIdPath;

    public static final String EX = "ex";
    public static final String JIRA = "jira";

    private final ThreadLocal<JiraIssue> jiraIssueHolder = ThreadLocal.withInitial(() -> null);

    public static final String REST_PATH = "rest";
    public static final String API_PATH = "api";
    public static final String VERSION_PATH = "3";

    public static final String ISSUE_PATH = "issue";
    private final WebClient webClient;

    public JiraService(@Qualifier(ATLASSIAN_API_WEB_CLIENT) WebClient webClient) {
        this.webClient = webClient;
    }

    public JiraIssue get(String jiraId) {
        String[] basePathSegments = {EX, JIRA, cloudIdPath, REST_PATH, API_PATH, VERSION_PATH, ISSUE_PATH, jiraId};

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(basePathSegments)
                        .build())
                .exchangeToMono(response -> response.bodyToMono(JiraIssue.class))
                .block();
    }

    public JiraIssue create(JiraIssue jiraIssue) {
        log.info("Creating jira issue.");

        if (jiraIssueHolder.get() != null) {
            throw new DuplicateJiraCreationException(jiraIssueHolder.get());
        }

        String[] basePathSegments = {EX, JIRA, cloudIdPath, REST_PATH, API_PATH, VERSION_PATH, ISSUE_PATH};

        jiraIssue = webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(basePathSegments)
                        .build())
                .bodyValue(jiraIssue)
                .exchangeToMono(response -> response.bodyToMono(JiraIssue.class))
                .block();

        assert jiraIssue != null;
        log.info("Created jira issue with key: {}", jiraIssue.key());
        jiraIssueHolder.set(jiraIssue);

        return jiraIssue;
    }

    public List<User> userSearch(String userName) {
        log.info("Searching for user: {}", userName);
        String[] basePathSegments = {EX, JIRA, cloudIdPath, REST_PATH, API_PATH, VERSION_PATH, USER_PATH, ASSIGNABLE_PATH, SEARCH_PATH};

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(basePathSegments)
                        .queryParam(QUERY_PARAM, userName)
                        .queryParam(PROJECT_PARAM, PROJECT_ID)
                        .build())
                .retrieve()
                .bodyToFlux(User.class)
                .collectList()
                .block();

    }
}
