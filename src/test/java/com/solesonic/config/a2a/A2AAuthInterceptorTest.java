package com.solesonic.config.a2a;

import com.solesonic.mcp.client.TokenExchangeService;
import com.solesonic.model.a2a.A2AElicitationExtension;
import com.solesonic.model.security.McpFilterService;
import org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class A2AAuthInterceptorTest {

    @Mock
    private TokenExchangeService tokenExchangeService;

    @Mock
    private McpFilterService mcpFilterService;

    @Test
    void everyCallAsksForTheElicitationExtension() {
        when(mcpFilterService.getClientCredentialsAccessToken()).thenReturn("service-token");

        A2AAuthInterceptor interceptor = new A2AAuthInterceptor(tokenExchangeService, mcpFilterService, null);

        PayloadAndHeaders payloadAndHeaders = interceptor.intercept("SendStreamingMessage", "payload", Map.of(), null, null);

        assertThat(payloadAndHeaders.getHeaders())
                .containsEntry("Authorization", "Bearer service-token")
                .containsEntry("A2A-Extensions", A2AElicitationExtension.URI);
    }
}
