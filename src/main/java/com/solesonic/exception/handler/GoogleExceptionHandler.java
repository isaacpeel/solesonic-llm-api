package com.solesonic.exception.handler;

import com.solesonic.exception.google.GoogleApiException;
import com.solesonic.exception.google.GoogleExceptionResponse;
import com.solesonic.exception.google.GoogleTokenException;
import com.solesonic.model.SolesonicChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.function.client.ClientResponse;

import java.net.URI;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Turns Google failures into something a user can act on. Google's own error text is logged, never
 * returned — it names internal identifiers and is written for developers, not end users.
 */
@ControllerAdvice
public class GoogleExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GoogleExceptionHandler.class);

    private final ExceptionService exceptionService;

    public GoogleExceptionHandler(ExceptionService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @ExceptionHandler(GoogleApiException.class)
    public ResponseEntity<GoogleExceptionResponse> handleGoogleApiException(GoogleApiException googleApiException) {
        ClientResponse clientResponse = googleApiException.getResponse();
        URI requestUri = clientResponse.request().getURI();

        log.error("Google API error calling {}", requestUri);

        GoogleExceptionResponse googleExceptionResponse =
                new GoogleExceptionResponse(requestUri.toASCIIString(), "Google API error");

        return new ResponseEntity<>(googleExceptionResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(GoogleTokenException.class)
    public ResponseEntity<SolesonicChatResponse> handleGoogleTokenException(GoogleTokenException googleTokenException) {
        HttpStatusCode statusCode = googleTokenException.getErrorCode();
        boolean retriable = googleTokenException.isRetriable();
        String message = googleTokenException.getMessage();

        return switch (statusCode) {
            case BAD_REQUEST -> {
                log.warn("Invalid Google token - {}", message);
                yield exceptionService.buildResponse("Invalid Google token. User must re-consent to Google access.");
            }
            case TOO_MANY_REQUESTS -> {
                log.warn("Rate limited by Google - {}", message);
                yield exceptionService.buildResponse("Temporary upstream service issue with Google.");
            }
            default -> {
                if (retriable) {
                    log.warn("Retriable Google API error - {}", message);
                    yield exceptionService.buildResponse("Google temporary service issue. Please try again.");
                }

                log.error("Non-retriable Google API error - {}", message);
                yield exceptionService.buildResponse("Internal service error. Please contact Isaac.");
            }
        };
    }
}
