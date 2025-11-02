package com.solesonic.service.security;

import com.solesonic.model.security.BodyCapturer;
import com.solesonic.model.security.MinimalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Service
public class RequestBodyService {
    private static final Logger log =  LoggerFactory.getLogger(RequestBodyService.class);

    /**
     * Captures the request body as a string by inserting it into a capturing wrapper.
     *
     * @param clientRequest the original client request
     * @return Mono containing the buffered JSON string
     */
    public String captureBody(ClientRequest clientRequest) {
        BodyInserter<?, ? super ClientHttpRequest> bodyInserter = clientRequest.body();

        Charset charset = determineCharset(clientRequest.headers());
        BodyCapturer bodyCapturer = new BodyCapturer(charset);
        MinimalContext context = new MinimalContext();

        MediaType mediaType = clientRequest.headers().getContentType();

        if (mediaType != null) {
            bodyCapturer.getHeaders().setContentType(mediaType);
        }

        log.debug("Starting body capture via insertion for request to {}", clientRequest.url());

        bodyInserter.insert(bodyCapturer, context).block();

        String capturedBody = bodyCapturer.getCapturedBody();

        log.debug("Captured body via insertion: {} bytes", capturedBody != null ? capturedBody.length() : 0);

        if (capturedBody == null) {
            throw new IllegalStateException("Failed to capture request body");
        }

        return capturedBody;
    }

    /**
     * Determines the charset from the Content-Type header, defaulting to UTF-8.
     *
     * @param headers the HTTP headers
     * @return the charset to use for body decoding
     */
    private Charset determineCharset(HttpHeaders headers) {
        MediaType contentType = headers.getContentType();

        if (contentType != null && contentType.getCharset() != null) {
            return contentType.getCharset();
        }

        return StandardCharsets.UTF_8;
    }
}
