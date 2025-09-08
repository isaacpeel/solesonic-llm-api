package com.solesonic.config.atlassian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.exception.atlassian.JiraException;
import com.solesonic.security.atlassian.AtlassianInternalAuthorizationFilter;
import com.solesonic.security.atlassian.AtlassianRequestAuthorizationFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.solesonic.config.atlassian.AtlassianConstants.*;

@SuppressWarnings("DuplicatedCode")
@Configuration
public class AtlassianClientConfig {
    @Value("${jira.api.uri}")
    private String jiraApiUri;

    @Value("${atlassian.oauth.token-uri}")
    private String jiraApiAuthUri;

    private final AtlassianRequestAuthorizationFilter atlassianRequestAuthorizationFilter;
    private final AtlassianInternalAuthorizationFilter atlassianInternalAuthorizationFilter;

    public AtlassianClientConfig(AtlassianRequestAuthorizationFilter atlassianRequestAuthorizationFilter,
                                 AtlassianInternalAuthorizationFilter atlassianInternalAuthorizationFilter) {
        this.atlassianRequestAuthorizationFilter = atlassianRequestAuthorizationFilter;
        this.atlassianInternalAuthorizationFilter = atlassianInternalAuthorizationFilter;
    }

    @Bean
    @Qualifier(ATLASSIAN_API_WEB_CLIENT)
    public WebClient jiraRequestApiWebClient(ObjectMapper objectMapper) {
        return WebClient.builder()
                .baseUrl(jiraApiUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
                    configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));

                })
                .filter(atlassianRequestAuthorizationFilter)
                .filter((request, next) -> next.exchange(request)
                        .flatMap(this::handleResponse))
                .build();
    }

    @Bean
    @Qualifier(ATLASSIAN_AUTH_WEB_CLIENT)
    public WebClient jiraAuthWebClient(ObjectMapper objectMapper) {
        return WebClient.builder()
                .baseUrl(jiraApiAuthUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
                    configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));

                })
                .filter((request, next) -> next.exchange(request)
                        .flatMap(this::handleResponse))
                .build();
    }

    @Bean
    @Qualifier(ATLASSIAN_API_INTERNAL_CLIENT)
    public WebClient jiraInternalApiWebClient(ObjectMapper objectMapper) {
        return WebClient.builder()
                .baseUrl(jiraApiUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_JSON));
                    configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_JSON));

                })
                .filter(atlassianInternalAuthorizationFilter)
                .filter((request, next) -> next.exchange(request)
                        .flatMap(this::handleResponse))
                .build();
    }

    private Mono<ClientResponse> handleResponse(ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return Mono.just(response);
        } else {
            return response.bodyToMono(String.class)
                    .flatMap(errorBody -> Mono.error(new JiraException(errorBody, response)));
        }
    }
}
