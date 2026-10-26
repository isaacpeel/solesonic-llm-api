package com.solesonic.exception.handler;

import com.solesonic.exception.llm.LlmKeyMissingException;
import com.solesonic.exception.llm.LlmKeyRejectedException;
import com.solesonic.model.llm.LlmKeyErrorCode;
import com.solesonic.model.llm.LlmKeyErrorResponse;
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
 * Real status codes for model-access failures, for the reasons {@link ChatModelExceptionHandler}
 * gives: not {@link ExceptionService}, highest precedence, and a fixed content type so a streaming
 * client that asked for {@code text/event-stream} still gets the status rather than a {@code 500}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class LlmKeyExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(LlmKeyExceptionHandler.class);

    @ExceptionHandler(LlmKeyMissingException.class)
    public ResponseEntity<LlmKeyErrorResponse> handleMissing(LlmKeyMissingException llmKeyMissingException) {
        log.info("Responding 403: the caller has no model access key");

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LlmKeyErrorResponse(LlmKeyErrorCode.LLM_KEY_MISSING, llmKeyMissingException.getMessage()));
    }

    @ExceptionHandler(LlmKeyRejectedException.class)
    public ResponseEntity<LlmKeyErrorResponse> handleRejected(LlmKeyRejectedException llmKeyRejectedException) {
        HttpStatus status = switch (llmKeyRejectedException.getErrorCode()) {
            case INVALID_LLM_KEY, LLM_KEY_MISSING -> HttpStatus.BAD_REQUEST;
            case LLM_KEY_VALIDATION_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        log.info("Responding {} to a model access key: {}", status, llmKeyRejectedException.getErrorCode());

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LlmKeyErrorResponse(llmKeyRejectedException.getErrorCode(), llmKeyRejectedException.getMessage()));
    }
}
