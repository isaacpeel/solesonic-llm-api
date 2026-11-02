package com.solesonic.config.openai;

import com.openai.client.OpenAIClient;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.models.images.ImageGenerateParams;
import com.solesonic.model.llm.LlmApiKey;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the image client against a real local endpoint: what matters is the route it resolves to, the
 * key it carries, and that a failed generation is never sent twice.
 */
class ImageOpenAiConfigTest {

    private static final String IMAGES_RESPONSE = """
            {"created":1761000000,"data":[{"b64_json":"iVBORw0KGgo="}],"output_format":"png","size":"1024x1024"}""";

    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    private HttpServer httpServer;

    @BeforeEach
    void startServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            requestedPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();

            byte[] body = (responseStatus.get() == 200 ? IMAGES_RESPONSE : "{\"error\":{\"message\":\"failed\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);

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
    void generatesOnTheConfiguredEndpointWithTheUsersKey() {
        OpenAIClient openAiClient = client();

        try {
            openAiClient.images().generate(params());

            assertThat(requestedPath.get()).isEqualTo("/v1/images/generations");
            assertThat(authorization.get()).isEqualTo("Bearer user-key-0001");
        } finally {
            openAiClient.close();
        }
    }

    /**
     * A retry is a second generation on a GPU with no admission control, and a timed-out one keeps
     * running upstream regardless.
     */
    @Test
    void neverRetriesAFailedGeneration() {
        responseStatus.set(500);
        OpenAIClient openAiClient = client();

        try {
            assertThatThrownBy(() -> openAiClient.images().generate(params()))
                    .isInstanceOf(InternalServerException.class);

            assertThat(requestCount.get()).isEqualTo(1);
        } finally {
            openAiClient.close();
        }
    }

    @Test
    void aCallOnTheServiceKeyOutsideAnyScopeNeverLeaves() {
        OpenAIClient openAiClient = client();

        try {
            ImageGenerateParams serviceKeyParams = ImageGenerateParams.builder()
                    .model("solesonic-image (izzy-bot)")
                    .prompt("a lighthouse")
                    .build();

            assertThatThrownBy(() -> openAiClient.images().generate(serviceKeyParams))
                    .isInstanceOf(OpenAIIoException.class);

            assertThat(requestCount.get()).isZero();
        } finally {
            openAiClient.close();
        }
    }

    private OpenAIClient client() {
        String baseUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/v1";

        return new ImageOpenAiConfig().imageOpenAiClient("service-key", baseUrl, Duration.ofSeconds(5),
                List.of(new UserLlmKeyConfig().userLlmKeyCustomizer("service-key")));
    }

    private static ImageGenerateParams params() {
        return ImageGenerateParams.builder()
                .model("solesonic-image (izzy-bot)")
                .prompt("a lighthouse")
                .putAdditionalHeader("Authorization", new LlmApiKey("user-key-0001").bearer())
                .build();
    }
}
