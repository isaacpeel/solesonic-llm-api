package com.solesonic.model.session;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code usernames} names the usernames a request could not resolve; it is absent from every other
 * error.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionErrorResponse(SessionErrorCode code, String message, List<String> usernames) {

    public SessionErrorResponse(SessionErrorCode code, String message) {
        this(code, message, null);
    }
}
