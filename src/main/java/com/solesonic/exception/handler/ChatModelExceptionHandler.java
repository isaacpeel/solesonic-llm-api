package com.solesonic.exception.handler;

import com.solesonic.exception.chat.ChatModelException;
import com.solesonic.model.chat.model.ChatModelErrorResponse;
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
 * Maps a model that could not be chosen onto a real status code. Deliberately not
 * {@link ExceptionService}: a {@code 200} meaning "it failed" reads as success to a REST caller.
 * <p>
 * {@link Ordered#HIGHEST_PRECEDENCE} for the reason {@link AttachmentExceptionHandler} gives:
 * {@link GeneralExceptionHandler}'s catch-all would otherwise answer first.
 * <p>
 * The content type is set rather than negotiated: a streaming client asks for
 * {@code text/event-stream} only, and negotiation would find no way to write the body and turn the
 * status into a {@code 500}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class ChatModelExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatModelExceptionHandler.class);

    @ExceptionHandler(ChatModelException.class)
    public ResponseEntity<ChatModelErrorResponse> handleChatModel(ChatModelException chatModelException) {
        HttpStatus status = switch (chatModelException.getErrorCode()) {
            case UNKNOWN_MODEL -> HttpStatus.BAD_REQUEST;
            case MODEL_LIST_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        log.info("Responding {} to a model choice: {}", status, chatModelException.getErrorCode());

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatModelErrorResponse(chatModelException.getErrorCode(), chatModelException.getMessage()));
    }
}
