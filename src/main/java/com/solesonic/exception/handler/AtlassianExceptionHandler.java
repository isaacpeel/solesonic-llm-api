package com.solesonic.exception.handler;

import com.solesonic.exception.atlassian.AtlassianTokenException;
import com.solesonic.exception.atlassian.DuplicateJiraCreationException;
import com.solesonic.exception.atlassian.JiraException;
import com.solesonic.exception.atlassian.JiraExceptionResponse;
import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.atlassian.jira.issue.JiraIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

@ControllerAdvice
public class AtlassianExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AtlassianExceptionHandler.class);

    private final ExceptionService exceptionService;

    @Value("${JIRA_URL_TEMPLATE}")
    private String jiraUrlTemplate;

    private static final String DUPLICATE_JIRA_MESSAGE_TEMPLATE = """
            I've gone into an error state but still managed managed to create your Jira issue.
            Here is a link: {issueLink}
            """;

    public static final String ISSUE_ID = "{issueId}";
    public static final String ISSUE_LINK = "{issueLink}";

    public AtlassianExceptionHandler(ExceptionService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @ExceptionHandler(JiraException.class)
    public ResponseEntity<JiraExceptionResponse> handleJiraException(JiraException jiraException) {
        ClientResponse clientResponse = jiraException.getResponse();
        URI requestUri = clientResponse.request().getURI();

        JiraExceptionResponse jiraExceptionResponse = new JiraExceptionResponse(requestUri.toASCIIString(), jiraException.getMessage());
        return new ResponseEntity<>(jiraExceptionResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(DuplicateJiraCreationException.class)
    public ResponseEntity<SolesonicChatResponse> handleDuplicateJiraException(DuplicateJiraCreationException duplicateJiraCreationException) {
        JiraIssue jiraIssue = duplicateJiraCreationException.getJiraIssue();
        String jiraUri = jiraUrlTemplate.replace(ISSUE_ID, jiraIssue.key());

        String responseMessage = DUPLICATE_JIRA_MESSAGE_TEMPLATE.replace(ISSUE_LINK, jiraUri);

        return exceptionService.buildResponse(responseMessage);
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<?> handleWebClientResponseException(WebClientResponseException webClientResponseException) {
        AtlassianTokenException atlassianTokenException;

        HttpStatusCode statusCode = webClientResponseException.getStatusCode();
        
        switch (statusCode) {
            case BAD_REQUEST -> {
                log.warn("Atlassian token refresh failed with 400 - likely invalid refresh token");
                atlassianTokenException = new AtlassianTokenException("Invalid refresh token", statusCode, false, webClientResponseException);
            }
            case TOO_MANY_REQUESTS -> {
                log.warn("Atlassian token refresh failed with retriable error: {}", webClientResponseException.getStatusCode());
                atlassianTokenException = new AtlassianTokenException("Upstream error", statusCode, true, webClientResponseException);
            }
            default-> {
                log.error("Unknown error: {}", webClientResponseException.getStatusCode());
                atlassianTokenException = new AtlassianTokenException("Atlassian API error", statusCode, false, webClientResponseException);
            } 
        }

        return handleAtlassianTokenException(atlassianTokenException);
    }

    @ExceptionHandler(AtlassianTokenException.class)
    public ResponseEntity<SolesonicChatResponse> handleAtlassianTokenException(AtlassianTokenException atlassianTokenException) {
        HttpStatusCode statusCode = atlassianTokenException.getErrorCode();
        boolean retriable = atlassianTokenException.isRetriable();
        String message = atlassianTokenException.getMessage();

        return switch (statusCode) {
            case BAD_REQUEST -> {
                log.warn("Invalid Atlassian token - {}", message);
                yield  exceptionService.buildResponse("Invalid Atlassian token. User must re-consent to Atlassian access.");
            }
            case TOO_MANY_REQUESTS -> {
                log.warn("Rate limited by Atlassian - {}", message);
                yield  exceptionService.buildResponse("Temporary upstream service issue with Atlassian.");
            }
            default -> {
                if (retriable) {
                    log.warn("Retriable Atlassian API error - {}", message);
                    yield  exceptionService.buildResponse("Atlassian temporary service issue. Please try again.");
                } else {
                    log.error("Non-retriable Atlassian API error - {}", message);
                    yield  exceptionService.buildResponse("Internal service error. Please contact Isaac.");
                }
            }
        };
    }
}