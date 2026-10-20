package com.solesonic.exception.handler;

import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushErrorResponse;
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
 * Maps a push failure onto a real status code, for the reason {@link ChatModelExceptionHandler}
 * gives: a {@code 200} meaning "it failed" reads as success to a REST caller.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class PushExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PushExceptionHandler.class);

    @ExceptionHandler(PushException.class)
    public ResponseEntity<PushErrorResponse> handlePush(PushException pushException) {
        HttpStatus status = switch (pushException.getErrorCode()) {
            case PUSH_DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNSUPPORTED_BROWSER, INVALID_SUBSCRIPTION, INVALID_PREFERENCES -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SUBSCRIPTION_GONE -> HttpStatus.GONE;
            case DELIVERY_FAILED -> HttpStatus.BAD_GATEWAY;
        };

        log.info("Responding {} to a push request: {}", status.value(), pushException.getErrorCode());

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PushErrorResponse(pushException.getErrorCode(), pushException.getMessage()));
    }
}
