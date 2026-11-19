package com.solesonic.exception.handler;

import com.solesonic.exception.agentupdate.AgentUpdateException;
import com.solesonic.model.agentupdate.AgentUpdateErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class AgentUpdateExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentUpdateExceptionHandler.class);

    @ExceptionHandler(AgentUpdateException.class)
    public ResponseEntity<AgentUpdateErrorResponse> handle(AgentUpdateException agentUpdateException) {
        HttpStatus status = switch (agentUpdateException.getErrorCode()) {
            case INVALID_UPDATE -> HttpStatus.BAD_REQUEST;
            case UNKNOWN_AGENT -> HttpStatus.FORBIDDEN;
            case USER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };

        log.info("Responding {} to an agent update: {}", status.value(), agentUpdateException.getErrorCode());

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AgentUpdateErrorResponse(agentUpdateException.getErrorCode(), agentUpdateException.getMessage()));
    }
}
