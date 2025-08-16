package com.solesonic.exception;

import org.springframework.web.reactive.function.client.ClientResponse;

public class JiraException extends RuntimeException {
    private ClientResponse response;
    private String responseBody;

    public JiraException(String responseBody, ClientResponse response) {
        super(responseBody);
        this.response = response;
        this.responseBody = responseBody;
    }

    public JiraException(String message) {
        super(message);
    }

    @SuppressWarnings("unused")
    public JiraException(String message, Throwable cause) {
        super(message, cause);
    }

    public ClientResponse getResponse() {
        return response;
    }

    @SuppressWarnings("unused")
    public String getResponseBody() {
        return responseBody;
    }
}
