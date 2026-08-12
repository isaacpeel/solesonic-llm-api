package com.solesonic.config.google;

import com.solesonic.exception.google.GoogleApiException;
import com.solesonic.security.google.GoogleRequestAuthorizationFilter;
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

import static com.solesonic.config.google.GoogleConstants.GOOGLE_API_WEB_CLIENT;
import static com.solesonic.config.google.GoogleConstants.GOOGLE_AUTH_WEB_CLIENT;

/**
 * The two clients Google needs: one for the OAuth2 endpoints, one for Gmail itself.
 * <p>
 * {@link GoogleRequestAuthorizationFilter} arrives as a bean-method parameter rather than through
 * the constructor on purpose. The filter depends on the refresh service, which depends on the
 * auth client defined here; taking the filter in the constructor would make constructing this
 * configuration require a bean that this configuration has to exist to produce.
 */
@Configuration
public class GoogleClientConfig {

    @Value("${google.oauth.base-uri}")
    private String googleOauthBaseUri;

    @Value("${google.api.uri}")
    private String googleApiUri;

    /**
     * Deliberately carries no error-handling filter. Google answers a revoked or expired refresh
     * token with 400 and a JSON body naming {@code invalid_grant}; that body is the only thing
     * that distinguishes "the user must re-consent" from "Google is having a bad day", so the
     * callers read it themselves rather than having a filter convert it to a generic failure.
     */
    @Bean
    @Qualifier(GOOGLE_AUTH_WEB_CLIENT)
    public WebClient googleAuthWebClient(JsonMapper jsonMapper) {
        return WebClient.builder()
                .baseUrl(googleOauthBaseUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .build();
    }

    @Bean
    @Qualifier(GOOGLE_API_WEB_CLIENT)
    public WebClient googleApiWebClient(JsonMapper jsonMapper,
                                        GoogleRequestAuthorizationFilter googleRequestAuthorizationFilter) {
        return WebClient.builder()
                .baseUrl(googleApiUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .filter(googleRequestAuthorizationFilter)
                .filter((request, next) -> next.exchange(request)
                        .flatMap(this::handleResponse))
                .build();
    }

    private Mono<ClientResponse> handleResponse(ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return Mono.just(response);
        }

        return response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new GoogleApiException(errorBody, response)));
    }
}
