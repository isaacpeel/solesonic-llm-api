package com.solesonic.exception.google;

import org.springframework.web.reactive.function.client.ClientResponse;

public class GoogleApiException extends RuntimeException {
    private final transient ClientResponse response;

    public GoogleApiException(String responseBody, ClientResponse response) {
        super(responseBody);
        this.response = response;
    }

    public ClientResponse getResponse() {
        return response;
    }
}
