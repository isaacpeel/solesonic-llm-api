package com.solesonic.exception.xero;

import org.springframework.web.reactive.function.client.ClientResponse;

/**
 * A non-2xx answer from the Xero API itself, raised by the response filter on the API
 * {@code WebClient}. Carries the {@link ClientResponse} so the handler can log which call failed;
 * the body it wraps is Xero's own wording and is never returned to a caller.
 */
public class XeroApiException extends RuntimeException {
    private final transient ClientResponse response;

    public XeroApiException(String responseBody, ClientResponse response) {
        super(responseBody);
        this.response = response;
    }

    public ClientResponse getResponse() {
        return response;
    }
}
