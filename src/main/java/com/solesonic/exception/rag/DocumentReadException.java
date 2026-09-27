package com.solesonic.exception.rag;

public class DocumentReadException extends RuntimeException {

    public DocumentReadException(String message) {
        super(message);
    }

    public DocumentReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
