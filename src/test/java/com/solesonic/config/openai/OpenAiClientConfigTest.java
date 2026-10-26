package com.solesonic.config.openai;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIIoException;
import com.openai.models.models.Model;
import com.openai.models.models.ModelListParams;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.service.llm.LlmCredentialScope;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the configured client against a real local HTTP endpoint, because what matters here is what
 * goes over the wire: the path the models route resolves to under the configured base URL, and the
 * credentials it carries.
 */
class OpenAiClientConfigTest {

    private static final String MODELS_RESPONSE = """
            {"object":"list","data":[
              {"id":"openai/gpt-4o","object":"model","created":0,"owned_by":"openai"},
              {"id":"qwen3:8b","object":"model","created":0,"owned_by":"local"}
            ]}""";

    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    private HttpServer httpServer;

    @BeforeEach
    void startServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));

            byte[] body = MODELS_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        httpServer.start();
    }

    @AfterEach
    void stopServer() {
        httpServer.stop(0);
    }

    @Test
    void listsModelsFromTheConfiguredEndpointWithTheConfiguredKey() {
        String baseUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/v1";

        OpenAIClient openAiClient = new OpenAiClientConfig()
                .openAiClient("service-key", baseUrl, Duration.ofSeconds(5), List.of());

        try {
            List<String> modelIds = openAiClient.models().list().data().stream()
                    .map(Model::id)
                    .toList();

            assertThat(modelIds).containsExactly("openai/gpt-4o", "qwen3:8b");
            assertThat(requestedPath.get()).isEqualTo("/v1/models");
            assertThat(authorization.get()).isEqualTo("Bearer service-key");
        } finally {
            openAiClient.close();
        }
    }

    /**
     * What every per-user call rests on: a request's own {@code Authorization} header replaces the
     * key the client was built with.
     */
    @Test
    void aRequestHeaderReplacesTheClientKey() {
        OpenAIClient openAiClient = userKeyGuardedClient();

        try {
            openAiClient.models().list(ModelListParams.builder()
                    .putAdditionalHeader("Authorization", new LlmApiKey("user-key-0001").bearer())
                    .build());

            assertThat(authorization.get()).isEqualTo("Bearer user-key-0001");
        } finally {
            openAiClient.close();
        }
    }

    @Test
    void aBlockingCallInsideAUserScopeCarriesTheUsersKey() {
        OpenAIClient openAiClient = userKeyGuardedClient();

        try {
            LlmCredentialScope.runAsUser(new LlmApiKey("user-key-0002"), () -> openAiClient.models().list());

            assertThat(authorization.get()).isEqualTo("Bearer user-key-0002");
        } finally {
            openAiClient.close();
        }
    }

    @Test
    void aCallOnTheServiceKeyOutsideAnyScopeNeverLeaves() {
        OpenAIClient openAiClient = userKeyGuardedClient();

        try {
            assertThatThrownBy(() -> openAiClient.models().list()).isInstanceOf(OpenAIIoException.class);

            assertThat(requestedPath.get()).isNull();
        } finally {
            openAiClient.close();
        }
    }

    @Test
    void theServiceScopeSendsTheServiceKey() {
        OpenAIClient openAiClient = userKeyGuardedClient();

        try {
            LlmCredentialScope.runAsService(() -> openAiClient.models().list());

            assertThat(authorization.get()).isEqualTo("Bearer service-key");
        } finally {
            openAiClient.close();
        }
    }

    private OpenAIClient userKeyGuardedClient() {
        String baseUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/v1";

        return new OpenAiClientConfig().openAiClient("service-key", baseUrl, Duration.ofSeconds(5),
                List.of(new UserLlmKeyConfig().userLlmKeyCustomizer("service-key")));
    }
}
