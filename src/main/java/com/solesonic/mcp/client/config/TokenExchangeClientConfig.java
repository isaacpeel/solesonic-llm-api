package com.solesonic.mcp.client.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class TokenExchangeClientConfig {

    public static final String MCP_TOKEN_EXCHANGE_CLIENT = "mcp-token-exchange-client";

    @Value("${solesonic.llm.token.exchange.endpoint}")
    private String tokenExchangeEndpoint;

    @Bean
    @Qualifier(MCP_TOKEN_EXCHANGE_CLIENT)
    public WebClient tokenExchangeWebClient() {
        return WebClient.builder()
                .baseUrl(tokenExchangeEndpoint)
                .defaultHeaders(headers -> headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED))
                .build();
    }
}
