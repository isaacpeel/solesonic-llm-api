package com.solesonic.exception.handler;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.model.image.ImageGenerationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps a failed image generation onto a status code and the same failure body the streaming
 * endpoint emits as its {@code error} frame.
 * <p>
 * Only the non-streaming endpoint reaches this. A streaming generation has already committed a
 * {@code 200} and its response body before it can fail, so it reports failure in-band instead.
 * <p>
 * {@link Ordered#HIGHEST_PRECEDENCE} for the same reason {@link AttachmentExceptionHandler} needs
 * it: {@link GeneralExceptionHandler}'s catch-all would otherwise turn this into a chat-shaped
 * {@code 200}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class ImageGenerationExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationExceptionHandler.class);

    @ExceptionHandler(ImageGenerationException.class)
    public ResponseEntity<ImageGenerationFailure> handleImageGeneration(ImageGenerationException imageGenerationException) {
        HttpStatus status = status(imageGenerationException);

        log.info("Responding {} to image generation: {}", status, imageGenerationException.getErrorCode());

        return ResponseEntity.status(status)
                .body(new ImageGenerationFailure(imageGenerationException.getErrorCode(),
                        imageGenerationException.getMessage()));
    }

    private static HttpStatus status(ImageGenerationException imageGenerationException) {
        return switch (imageGenerationException.getErrorCode()) {
            case INVALID_PROMPT -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case GENERATION_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case BACKEND_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
