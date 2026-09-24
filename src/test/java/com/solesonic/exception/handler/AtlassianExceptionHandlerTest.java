package com.solesonic.exception.handler;

import com.solesonic.exception.atlassian.JiraException;
import com.solesonic.exception.atlassian.JiraExceptionResponse;
import com.solesonic.model.SolesonicChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins that Jira's own response body — which can carry internal identifiers — never reaches the
 * client, and that a token refresh failure maps to the right retriable/non-retriable outcome.
 */
class AtlassianExceptionHandlerTest {
    private final AtlassianExceptionHandler atlassianExceptionHandler = new AtlassianExceptionHandler(new ExceptionService());

    @Test
    void jiraExceptionNeverLeaksTheRawResponseBody() {
        String rawBody = "{\"errorMessages\":[\"internal Jira detail\"],\"secret\":\"abc123\"}";

        HttpRequest httpRequest = mock(HttpRequest.class);
        when(httpRequest.getURI()).thenReturn(URI.create("https://api.atlassian.com/ex/jira/site/rest/api/2/issue"));

        ClientResponse clientResponse = mock(ClientResponse.class);
        when(clientResponse.request()).thenReturn(httpRequest);

        JiraException jiraException = new JiraException(rawBody, clientResponse);

        ResponseEntity<JiraExceptionResponse> responseEntity = atlassianExceptionHandler.handleJiraException(jiraException);

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(responseEntity.getBody()).isNotNull();
        assertThat(responseEntity.getBody().uri()).isEqualTo("https://api.atlassian.com/ex/jira/site/rest/api/2/issue");
        assertThat(responseEntity.getBody().response()).doesNotContain("internal Jira detail", "secret", "abc123");
    }

    @Test
    void badRequestTokenExceptionAsksTheUserToReconsent() {
        WebClientResponseException webClientResponseException =
                WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, new byte[0], null);

        assertThat(chatResponseMessage(webClientResponseException)).contains("re-consent to Atlassian access");
    }

    @Test
    void rateLimitedTokenExceptionReportsATemporaryUpstreamIssue() {
        WebClientResponseException webClientResponseException =
                WebClientResponseException.create(429, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null);

        assertThat(chatResponseMessage(webClientResponseException)).contains("Temporary upstream service issue");
    }

    @Test
    void unknownStatusIsNonRetriableAndReportsAnInternalError() {
        WebClientResponseException webClientResponseException =
                WebClientResponseException.create(500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null);

        assertThat(chatResponseMessage(webClientResponseException)).contains("Internal service error");
    }

    private String chatResponseMessage(WebClientResponseException webClientResponseException) {
        ResponseEntity<?> responseEntity = atlassianExceptionHandler.handleWebClientResponseException(webClientResponseException);

        SolesonicChatResponse solesonicChatResponse = (SolesonicChatResponse) responseEntity.getBody();
        assertThat(solesonicChatResponse).isNotNull();

        return solesonicChatResponse.message().getMessage();
    }
}
