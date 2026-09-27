package com.solesonic.config.xero;

import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.security.xero.XeroRequestAuthorizationFilter;
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

import static com.solesonic.config.xero.XeroConstants.XERO_API_WEB_CLIENT;
import static com.solesonic.config.xero.XeroConstants.XERO_AUTH_WEB_CLIENT;

/**
 * The two clients Xero needs: one for the OAuth2 token endpoint, one for the Xero API itself.
 * Shaped after {@code GoogleClientConfig}, because Xero's OAuth mechanics are Google's — a
 * form-urlencoded token endpoint and one renewable credential per connection — rather than
 * Atlassian's.
 */
@Configuration
public class XeroClientConfig {

    @Value("${xero.oauth.base-uri}")
    private String xeroOauthBaseUri;

    @Value("${xero.api.uri}")
    private String xeroApiUri;

    /**
     * Deliberately carries no error-handling filter. Xero answers a revoked, expired or already-used
     * grant with a JSON body naming {@code invalid_grant}, and that body is the only thing that
     * distinguishes "the user must reconnect" from "Xero is having a bad day". A filter that
     * collapsed both into one exception would take that distinction away from the callers, which is
     * exactly what decides whether a failure is worth retrying.
     */
    @Bean
    @Qualifier(XERO_AUTH_WEB_CLIENT)
    public WebClient xeroAuthWebClient(JsonMapper jsonMapper) {
        return WebClient.builder()
                .baseUrl(xeroOauthBaseUri)
                .defaultHeaders(httpHeaders -> httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON)))
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .build();
    }

    /**
     * The client for calls made on a <em>stored</em> connection, and the one that turns a non-2xx
     * answer into a {@link XeroApiException}.
     * <p>
     * Nothing in the connect flow uses it: {@code GET /connections} runs during the OAuth callback,
     * before any token or tenant has been stored, so it carries its own {@code Authorization} header
     * on the auth client instead. This is the client every Accounting API call goes out on.
     * <p>
     * {@link XeroRequestAuthorizationFilter} arrives as a bean-method parameter rather than through
     * the constructor on purpose. The filter depends on the refresh service, which depends on the
     * auth client defined here; taking the filter in the constructor would make constructing this
     * configuration require a bean that this configuration has to exist to produce.
     * <p>
     * The filter order is load-bearing. Authorization runs first, so the error-handling filter below
     * it only ever reports on a request that actually carried credentials — reversed, an expired
     * token would surface as an opaque {@link XeroApiException} instead of the retriable-or-not
     * {@code XeroTokenException} the refresh path raises.
     */
    @Bean
    @Qualifier(XERO_API_WEB_CLIENT)
    public WebClient xeroApiWebClient(JsonMapper jsonMapper,
                                      XeroRequestAuthorizationFilter xeroRequestAuthorizationFilter) {
        return WebClient.builder()
                .baseUrl(xeroApiUri)
                .defaultHeaders(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .filter(xeroRequestAuthorizationFilter)
                .filter((request, next) -> next.exchange(request)
                        .flatMap(this::handleResponse))
                .build();
    }

    private Mono<ClientResponse> handleResponse(ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return Mono.just(response);
        }

        return response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new XeroApiException(errorBody, response)));
    }
}
