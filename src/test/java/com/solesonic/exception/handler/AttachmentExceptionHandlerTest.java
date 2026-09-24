package com.solesonic.exception.handler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that a {@link ResponseStatusException}'s {@code reason} reaches the client instead of being
 * discarded behind a bare status code.
 */
class AttachmentExceptionHandlerTest {
    private final AttachmentExceptionHandler attachmentExceptionHandler = new AttachmentExceptionHandler();

    @Test
    void responseStatusReasonReachesTheBody() {
        ResponseStatusException responseStatusException =
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such ingested document");

        ResponseEntity<StatusFailure> responseEntity = attachmentExceptionHandler.handleResponseStatus(responseStatusException);

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(responseEntity.getBody()).isEqualTo(new StatusFailure("No such ingested document"));
    }

    @Test
    void maxUploadSizeExceededIsContentTooLarge() {
        MaxUploadSizeExceededException maxUploadSizeExceededException = new MaxUploadSizeExceededException(1024L);

        ResponseEntity<Void> responseEntity = attachmentExceptionHandler.handleMaxUploadSize(maxUploadSizeExceededException);

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }
}
