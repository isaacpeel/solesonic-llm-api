package com.solesonic.exception.handler;

import com.solesonic.exception.google.GoogleErrorResponse;
import com.solesonic.exception.google.GoogleTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * A Google failure must arrive as a failing status code.
 * <p>
 * The Atlassian handler answers with {@code 200 OK} carrying a chat message, which is right for
 * failures that surface mid-conversation and wrong for these endpoints: the OAuth callback answers
 * {@code 204} on success, so a {@code 200} failure would make the browser's client treat "it broke"
 * as "it worked", and the token broker's MCP callers have only the status line to branch on.
 */
class GoogleExceptionHandlerTest {

    private final GoogleExceptionHandler googleExceptionHandler = new GoogleExceptionHandler();

    @Test
    void answersBadRequestWithReconnectRequiredWhenTheGrantIsGone() {
        GoogleTokenException googleTokenException =
                new GoogleTokenException("invalid_grant", BAD_REQUEST, false);

        ResponseEntity<GoogleErrorResponse> response =
                googleExceptionHandler.handleGoogleTokenException(googleTokenException);

        assertThat(response.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(GoogleErrorResponse.RECONNECT_REQUIRED);
    }

    @Test
    void answersServiceUnavailableWhenTheFailureIsRetriable() {
        GoogleTokenException googleTokenException =
                new GoogleTokenException("backend_error", SERVICE_UNAVAILABLE, true);

        ResponseEntity<GoogleErrorResponse> response =
                googleExceptionHandler.handleGoogleTokenException(googleTokenException);

        assertThat(response.getStatusCode()).isEqualTo(SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(GoogleErrorResponse.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void answersTooManyRequestsWhenGoogleThrottles() {
        GoogleTokenException googleTokenException =
                new GoogleTokenException("rate_limit", TOO_MANY_REQUESTS, true);

        ResponseEntity<GoogleErrorResponse> response =
                googleExceptionHandler.handleGoogleTokenException(googleTokenException);

        assertThat(response.getStatusCode()).isEqualTo(TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(GoogleErrorResponse.RATE_LIMITED);
    }

    @Test
    void answersInternalServerErrorWhenTheFailureIsNotRetriable() {
        GoogleTokenException googleTokenException =
                new GoogleTokenException("boom", INTERNAL_SERVER_ERROR, false);

        ResponseEntity<GoogleErrorResponse> response =
                googleExceptionHandler.handleGoogleTokenException(googleTokenException);

        assertThat(response.getStatusCode()).isEqualTo(INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(GoogleErrorResponse.INTERNAL);
    }

    /**
     * Google's own wording names internal identifiers; only the fixed, user-safe text may leave.
     */
    @Test
    void neverReturnsGooglesOwnErrorText() {
        GoogleTokenException googleTokenException =
                new GoogleTokenException("invalid_grant: token revoked for project 761157506466", BAD_REQUEST, false);

        ResponseEntity<GoogleErrorResponse> response =
                googleExceptionHandler.handleGoogleTokenException(googleTokenException);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain("invalid_grant");
        assertThat(response.getBody().message()).doesNotContain("761157506466");
    }
}
