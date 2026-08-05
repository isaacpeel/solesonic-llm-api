package com.solesonic.exception.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maps status-carrying exceptions to real HTTP status codes.
 * <p>
 * {@link Ordered#HIGHEST_PRECEDENCE} is required, not cosmetic: handler methods are matched by
 * exception specificity only <em>within</em> a single advice. Across advices Spring uses the first
 * bean that has any matching method, and {@link GeneralExceptionHandler} declares a catch-all
 * {@code @ExceptionHandler(Exception.class)} that would otherwise swallow these into a chat-shaped
 * {@code 200}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class AttachmentExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AttachmentExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Void> handleResponseStatus(ResponseStatusException responseStatusException) {
        log.debug("Responding {} for {}", responseStatusException.getStatusCode(), responseStatusException.getReason());

        return ResponseEntity.status(responseStatusException.getStatusCode()).build();
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Void> handleMaxUploadSize(MaxUploadSizeExceededException maxUploadSizeExceededException) {
        log.info("Upload rejected, exceeds configured multipart limit: {}",
                maxUploadSizeExceededException.getMessage());

        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }
}
