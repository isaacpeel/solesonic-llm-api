package com.solesonic.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static com.solesonic.mcp.client.config.TokenExchangeClientConfig.MCP_TOKEN_EXCHANGE_CLIENT;

@Service
public class TokenExchangeService {
    public static final String TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String GRANT_TYPE = "grant_type";
    public static final String CLIENT_ID = "client_id";
    public static final String CLIENT_SECRET = "client_secret";
    public static final String SUBJECT_TOKEN = "subject_token";
    public static final String TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    public static final String REQUESTED_TOKEN_TYPE = "requested_token_type";
    public static final String ACCESS_TOKEN = "access_token";


    private final WebClient tokenExchangeClient;

    @Value("${solesonic.llm.token.exchange.client-id}")
    private String tokenExchangeClientId;

    @Value("${solesonic.llm.token.exchange.client-secret}")
    private String tokenExchangeClientSecret;

    public TokenExchangeService(@Qualifier(MCP_TOKEN_EXCHANGE_CLIENT) WebClient tokenExchangeClient) {
        this.tokenExchangeClient = tokenExchangeClient;
    }

    public Mono<String> exchangeToken(String subjectToken) {
        return tokenExchangeClient.post()
                .body(BodyInserters.fromFormData(GRANT_TYPE, TOKEN_EXCHANGE_GRANT_TYPE)
                        .with(CLIENT_ID, tokenExchangeClientId)
                        .with(CLIENT_SECRET, tokenExchangeClientSecret)
                        .with(SUBJECT_TOKEN, subjectToken)
                        .with(REQUESTED_TOKEN_TYPE, TOKEN_TYPE_ACCESS_TOKEN))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> json.get(ACCESS_TOKEN).asText())
                .switchIfEmpty(Mono.error(new IllegalStateException("Token exchange returned no access_token")));
    }
}
