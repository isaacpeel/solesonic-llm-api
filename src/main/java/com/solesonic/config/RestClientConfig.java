package com.solesonic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(@Value("${solesonic.llm.uri.ingestion.read-timeout:30s}") Duration readTimeout) {

        HttpClientSettings httpClientSettings = HttpClientSettings.defaults()
                .withReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(httpClientSettings));
    }
}
