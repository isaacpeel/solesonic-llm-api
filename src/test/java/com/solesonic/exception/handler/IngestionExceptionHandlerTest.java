package com.solesonic.exception.handler;

import com.solesonic.exception.ChatException;
import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.rag.IngestionFailure;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that a document/URI ingestion failure comes back as a real status code with the message the
 * throwing service wrote, rather than {@link GeneralExceptionHandler}'s chat-shaped {@code 200}.
 */
class IngestionExceptionHandlerTest {
    private final IngestionExceptionHandler ingestionExceptionHandler = new IngestionExceptionHandler();

    @Test
    void chatExceptionBecomesABadRequestWithItsOwnMessage() {
        ResponseEntity<IngestionFailure> responseEntity =
                ingestionExceptionHandler.handleChatException(new ChatException("Uri must not be blank"));

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(responseEntity.getBody()).isEqualTo(new IngestionFailure("Uri must not be blank"));
    }

    @Test
    void documentReadExceptionBecomesABadRequestWithItsOwnMessage() {
        ResponseEntity<IngestionFailure> responseEntity = ingestionExceptionHandler.handleDocumentReadException(
                new DocumentReadException("No readable text extracted from notes.pdf"));

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(responseEntity.getBody()).isEqualTo(new IngestionFailure("No readable text extracted from notes.pdf"));
    }
}
