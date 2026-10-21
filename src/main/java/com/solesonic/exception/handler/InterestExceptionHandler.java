package com.solesonic.exception.handler;

import com.solesonic.exception.interest.InterestException;
import com.solesonic.model.interest.InterestErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps an interest failure onto a real status code, for the reason {@link ChatModelExceptionHandler}
 * gives: a {@code 200} meaning "it failed" reads as success to a REST caller.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class InterestExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(InterestExceptionHandler.class);

    @ExceptionHandler(InterestException.class)
    public ResponseEntity<InterestErrorResponse> handleInterest(InterestException interestException) {
        HttpStatus status = switch (interestException.getErrorCode()) {
            case OFFER_NOT_FOUND, AGENT_NOT_FOUND, UPDATE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OFFER_ALREADY_DECIDED, OFFER_EXPIRED, AGENT_LIMIT_REACHED -> HttpStatus.CONFLICT;
            case OFFER_WITHDRAWN -> HttpStatus.GONE;
            case INVALID_AGENT_UPDATE -> HttpStatus.BAD_REQUEST;
            case NOT_PERMITTED -> HttpStatus.FORBIDDEN;
            case RUN_TOO_SOON -> HttpStatus.TOO_MANY_REQUESTS;
        };

        log.info("Responding {} to an interest request: {}", status.value(), interestException.getErrorCode());

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new InterestErrorResponse(interestException.getErrorCode(), interestException.getMessage()));
    }
}
