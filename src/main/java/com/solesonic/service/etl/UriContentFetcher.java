package com.solesonic.service.etl;

import com.solesonic.exception.ChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.springframework.http.MediaType.TEXT_HTML_VALUE;

@Service
public class UriContentFetcher {
    private static final Logger log = LoggerFactory.getLogger(UriContentFetcher.class);

    private final RestClient restClient;
    private final DataSize maxBytes;

    public UriContentFetcher(@Value("${solesonic.llm.uri.ingestion.read-timeout:30s}") Duration readTimeout,
                             @Value("${solesonic.llm.uri.ingestion.max-bytes:20MB}") DataSize maxBytes) {
        this(buildRestClient(readTimeout), maxBytes);
    }

    UriContentFetcher(RestClient restClient, DataSize maxBytes) {
        this.restClient = restClient;
        this.maxBytes = maxBytes;
    }

    private static RestClient buildRestClient(Duration readTimeout) {
        HttpClientSettings httpClientSettings = HttpClientSettings.defaults()
                .withReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(httpClientSettings))
                .build();
    }

    public FetchedContent fetch(String uri) {
        log.info("Fetching uri content: {}", uri);

        ResponseEntity<byte[]> response = restClient.get()
                .uri(uri)
                .retrieve()
                .toEntity(byte[].class);

        byte[] data = response.getBody();

        if (data == null) {
            throw new ChatException("No content returned from uri: " + uri);
        }

        if (data.length > maxBytes.toBytes()) {
            throw new ChatException("Content at uri exceeds maximum allowed size of " + maxBytes + ": " + uri);
        }

        String contentType = normalizeContentType(response.getHeaders().getContentType());

        return new FetchedContent(data, contentType);
    }

    private static String normalizeContentType(MediaType mediaType) {
        if (mediaType == null) {
            return TEXT_HTML_VALUE;
        }

        return mediaType.getType().toLowerCase() + "/" + mediaType.getSubtype().toLowerCase();
    }

    public record FetchedContent(byte[] data, String contentType) {
    }
}
