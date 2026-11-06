package com.solesonic.exception.interest;

/**
 * A news search that did not answer: an {@code isError} result, a transport failure, or a body that
 * would not parse. It fails the run, never the caller, and its message is logged at class level only.
 */
public class NewsSearchException extends RuntimeException {

    public NewsSearchException(String message) {
        super(message);
    }

    public NewsSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
