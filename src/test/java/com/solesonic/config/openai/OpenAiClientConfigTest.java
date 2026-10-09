package com.solesonic.config.openai;

import com.openai.client.OpenAIClient;
import com.openai.models.models.Model;
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
                .openAiClient("service-key", baseUrl, Duration.ofSeconds(5));

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
}
