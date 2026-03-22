package com.solesonic.config.atlassian;

import com.solesonic.exception.atlassian.JiraException;
import com.solesonic.security.atlassian.AtlassianInternalAuthorizationFilter;
import com.solesonic.security.atlassian.AtlassianRequestAuthorizationFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

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
    public WebClient jiraRequestApiWebClient(JsonMapper jsonMapper) {
        return WebClient.builder()
                .baseUrl(jiraApiUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .filter(atlassianRequestAuthorizationFilter)
                .filter((request, next) -> next.exchange(request)
                        .flatMap(this::handleResponse))
                .build();
    }

    @Bean
    @Qualifier(ATLASSIAN_AUTH_WEB_CLIENT)
    public WebClient jiraAuthWebClient(JsonMapper jsonMapper) {
        return WebClient.builder()
                .baseUrl(jiraApiAuthUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .filter((request, next) -> next.exchange(request)
                        .flatMap(this::handleResponse))
                .build();
    }

    @Bean
    @Qualifier(ATLASSIAN_API_INTERNAL_CLIENT)
    public WebClient jiraInternalApiWebClient(JsonMapper jsonMapper) {
        return WebClient.builder()
                .baseUrl(jiraApiUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
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
