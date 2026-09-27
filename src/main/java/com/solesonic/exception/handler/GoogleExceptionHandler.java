package com.solesonic.exception.handler;

import com.solesonic.exception.google.GoogleApiException;
import com.solesonic.exception.google.GoogleErrorResponse;
import com.solesonic.exception.google.GoogleTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.function.client.ClientResponse;

import java.net.URI;

import static com.solesonic.exception.google.GoogleErrorResponse.INTERNAL;
import static com.solesonic.exception.google.GoogleErrorResponse.RATE_LIMITED;
import static com.solesonic.exception.google.GoogleErrorResponse.RECONNECT_REQUIRED;
import static com.solesonic.exception.google.GoogleErrorResponse.UPSTREAM_UNAVAILABLE;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Turns Google failures into something a caller can act on: a real status code and a stable error
 * code. Google's own error text is logged, never returned.
 * <p>
 * Deliberately <em>not</em> built on {@link ExceptionService}, the way
 * {@code AtlassianExceptionHandler} is. That renders a failure as {@code 200 OK} carrying a chat
 * message, which suits Atlassian because its failures surface mid-conversation. Google's endpoints
 * are plain REST — the OAuth callback and the token broker — and a 200 that means "it failed" forces
 * every caller to inspect a body to discover it, including MCP servers that only have the status
 * line to go on.
 */
@ControllerAdvice
public class GoogleExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GoogleExceptionHandler.class);

    private static final String RECONNECT_MESSAGE = "Google access is no longer valid. Reconnect your Google account.";
    private static final String RATE_LIMITED_MESSAGE = "Google is rate limiting requests. Please try again shortly.";
    private static final String UPSTREAM_MESSAGE = "Google is temporarily unavailable. Please try again.";
    private static final String INTERNAL_MESSAGE = "Internal service error. Please contact Isaac.";

    @ExceptionHandler(GoogleApiException.class)
    public ResponseEntity<GoogleErrorResponse> handleGoogleApiException(GoogleApiException googleApiException) {
        ClientResponse clientResponse = googleApiException.getResponse();
        URI requestUri = clientResponse.request().getURI();

        log.error("Google API error calling {}", requestUri);

        return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                .body(new GoogleErrorResponse(UPSTREAM_UNAVAILABLE, UPSTREAM_MESSAGE));
    }

    @ExceptionHandler(GoogleTokenException.class)
    public ResponseEntity<GoogleErrorResponse> handleGoogleTokenException(GoogleTokenException googleTokenException) {
        HttpStatusCode statusCode = googleTokenException.getErrorCode();
        boolean retriable = googleTokenException.isRetriable();
        String message = googleTokenException.getMessage();

        if (BAD_REQUEST.equals(statusCode)) {
            log.warn("Invalid Google token - {}", message);

            return ResponseEntity.status(BAD_REQUEST)
                    .body(new GoogleErrorResponse(RECONNECT_REQUIRED, RECONNECT_MESSAGE));
        }

        if (TOO_MANY_REQUESTS.equals(statusCode)) {
            log.warn("Rate limited by Google - {}", message);

            return ResponseEntity.status(TOO_MANY_REQUESTS)
                    .body(new GoogleErrorResponse(RATE_LIMITED, RATE_LIMITED_MESSAGE));
        }

        if (retriable) {
            log.warn("Retriable Google API error - {}", message);

            return ResponseEntity.status(SERVICE_UNAVAILABLE)
                    .body(new GoogleErrorResponse(UPSTREAM_UNAVAILABLE, UPSTREAM_MESSAGE));
        }

        log.error("Non-retriable Google API error - {}", message);

        return ResponseEntity.status(INTERNAL_SERVER_ERROR)
                .body(new GoogleErrorResponse(INTERNAL, INTERNAL_MESSAGE));
    }
}
