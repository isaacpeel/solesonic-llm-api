package com.solesonic.exception.handler;

import com.solesonic.exception.ChatException;
import com.solesonic.exception.rag.DocumentReadException;
import com.solesonic.model.rag.IngestionFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps a document/URI ingestion failure onto a real status code and a small typed body, for the
 * plain REST endpoints under {@code /documents} and {@code /chats/documents}.
 * <p>
 * Without this, both exception types fall through to {@link GeneralExceptionHandler}'s catch-all,
 * which answers {@code 200 OK} with a chat-shaped {@code SolesonicChatResponse} — nonsensical, and
 * indistinguishable from success, for a caller uploading a document rather than chatting. Neither
 * exception carries a raw upstream body: {@link ChatException} and {@link DocumentReadException} are
 * both raised with a message already written for a caller at every current throw site, so it is
 * returned as-is.
 * <p>
 * {@link Ordered#HIGHEST_PRECEDENCE} for the same reason {@link AttachmentExceptionHandler} needs
 * it: {@link GeneralExceptionHandler}'s catch-all would otherwise win.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class IngestionExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(IngestionExceptionHandler.class);

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<IngestionFailure> handleChatException(ChatException chatException) {
        log.warn("Ingestion request rejected: {}", chatException.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new IngestionFailure(chatException.getMessage()));
    }

    @ExceptionHandler(DocumentReadException.class)
    public ResponseEntity<IngestionFailure> handleDocumentReadException(DocumentReadException documentReadException) {
        log.warn("Ingestion request rejected: {}", documentReadException.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new IngestionFailure(documentReadException.getMessage()));
    }
}
