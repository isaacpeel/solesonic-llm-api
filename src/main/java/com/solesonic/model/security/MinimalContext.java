package com.solesonic.model.security;

import jakarta.annotation.Nonnull;
import org.springframework.core.codec.ByteArrayEncoder;
import org.springframework.core.codec.ResourceEncoder;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.BodyInserter;

import java.util.*;

public class MinimalContext implements BodyInserter.Context {
    private final List<HttpMessageWriter<?>> writers;

    public MinimalContext() {
        List<HttpMessageWriter<?>> httpMessageWriters = new ArrayList<>();

        Jackson2JsonEncoder jackson2JsonEncoder = new Jackson2JsonEncoder();
        httpMessageWriters.add(new EncoderHttpMessageWriter<>(jackson2JsonEncoder));

        ByteArrayEncoder byteArrayEncoder = new ByteArrayEncoder();
        httpMessageWriters.add(new EncoderHttpMessageWriter<>(byteArrayEncoder));

        ResourceEncoder resourceEncoder = new ResourceEncoder();
        httpMessageWriters.add(new EncoderHttpMessageWriter<>(resourceEncoder));
        this.writers = Collections.unmodifiableList(httpMessageWriters);
    }

    @Override
    @Nonnull
    public List<HttpMessageWriter<?>> messageWriters() {
        return writers;
    }

    @Override
    @Nonnull
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Optional serverRequest() {
        return Optional.empty();
    }

    @Override
    @Nonnull
    public Map<String, Object> hints() {
        return Map.of();
    }
}
