package com.solesonic.exception.handler;

import com.solesonic.exception.session.SessionException;
import com.solesonic.model.session.SessionErrorResponse;
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
 * Maps a shared-session request that cannot go ahead onto a real status code, for the reason
 * {@link ChatModelExceptionHandler} gives: a {@code 200} meaning "it failed" reads as success to a REST
 * caller. The content type is set rather than negotiated, so the stream endpoint can answer too.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class SessionExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(SessionExceptionHandler.class);

    @ExceptionHandler(SessionException.class)
    public ResponseEntity<SessionErrorResponse> handleSession(SessionException sessionException) {
        HttpStatus status = switch (sessionException.getErrorCode()) {
            case SESSION_NOT_FOUND, INVITE_NOT_FOUND, PARTICIPANT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_INVITE, INVALID_MESSAGE, MESSAGE_TOO_LONG -> HttpStatus.BAD_REQUEST;
            case INVITE_PENDING, INVITE_NOT_PENDING, SESSION_CLOSED, RUN_IN_PROGRESS, NOT_YOUR_TURN -> HttpStatus.CONFLICT;
            case TOO_MANY_INVITES -> HttpStatus.TOO_MANY_REQUESTS;
            case AGENT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        log.info("Responding {} to a shared-session request: {}", status, sessionException.getErrorCode());

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SessionErrorResponse(sessionException.getErrorCode(), sessionException.getMessage()));
    }
}
