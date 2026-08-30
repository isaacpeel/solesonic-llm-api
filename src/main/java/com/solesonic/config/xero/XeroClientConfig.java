package com.solesonic.config.xero;

import com.solesonic.exception.xero.XeroApiException;
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
     * It has no consumer yet — nothing in the connect flow uses it. That is not an oversight:
     * {@code GET /connections} runs during the OAuth callback, before any token or tenant has been
     * stored, so it carries its own {@code Authorization} header on the auth client instead. This
     * bean is what the Accounting API calls will use, once the request-authorization filter that
     * supplies {@code Authorization} and {@code xero-tenant-id} from the stored token exists.
     */
    @Bean
    @Qualifier(XERO_API_WEB_CLIENT)
    public WebClient xeroApiWebClient(JsonMapper jsonMapper) {
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
