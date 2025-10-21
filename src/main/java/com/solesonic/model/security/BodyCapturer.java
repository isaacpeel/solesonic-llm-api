package com.solesonic.model.security;

import jakarta.annotation.Nonnull;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class BodyCapturer implements ClientHttpRequest {
    private static final Logger log =  LoggerFactory.getLogger(BodyCapturer.class);
    
    private final HttpHeaders httpHeaders = new HttpHeaders();
    private final Charset charset;
    private volatile String capturedBody;

    public BodyCapturer(Charset charset) {
        this.charset = charset;
    }

    @Override
    @Nonnull
    public Mono<Void> writeWith(@Nonnull Publisher<? extends DataBuffer> body) {
        log.debug("BodyCapturer.writeWith() called");

        return DataBufferUtils.join(body)
                .doOnNext(dataBuffer -> {
                    try {
                        this.capturedBody = dataBuffer.toString(charset);
                        log.debug("Body captured in writeWith: {} bytes", this.capturedBody != null ? this.capturedBody.length() : 0);
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                })
                .then();
    }

    @Override
    @Nonnull
    public Mono<Void> writeAndFlushWith(@Nonnull Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return writeWith(Flux.from(body).flatMap(publisher -> publisher));
    }

    @Override
    @Nonnull
    public Mono<Void> setComplete() {
        return Mono.empty();
    }

    @Override
    @Nonnull
    public HttpHeaders getHeaders() {
        return httpHeaders;
    }

    @Override
    @Nonnull
    public DataBufferFactory bufferFactory() {
        return DefaultDataBufferFactory.sharedInstance;
    }

    @Override
    @Nonnull
    public HttpMethod getMethod() {
        return HttpMethod.POST;
    }

    @Override
    @Nonnull
    public URI getURI() {
        try {
            return new URI("http://localhost");
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid URI", exception);
        }
    }

    @Override
    public void beforeCommit(@Nonnull Supplier<? extends Mono<Void>> action) {
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    @Nonnull
    public MultiValueMap<String, HttpCookie> getCookies() {
        return new LinkedMultiValueMap<>();
    }

    @Override
    @Nonnull
    public <T> T getNativeRequest() {
        throw new UnsupportedOperationException("getNativeRequest not supported");
    }

    @Override
    @Nonnull
    public Map<String, Object> getAttributes() {
        return new HashMap<>();
    }

    public String getCapturedBody() {
        return capturedBody;
    }
}
