package com.solesonic.exception.push;

/**
 * A push that could not be built: encryption or VAPID signing failed. Never retriable, since the
 * same input fails the same way.
 */
public class WebPushException extends RuntimeException {

    public WebPushException(String message, Throwable cause) {
        super(message, cause);
    }
}
