package com.solesonic.service.image;

import com.openai.client.OpenAIClient;
import com.solesonic.config.openai.ImageOpenAiConfig;
import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.image.GeneratedImageBytes;
import com.solesonic.model.image.ImageGenerationErrorCode;
import com.solesonic.model.llm.LlmApiKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.solesonic.model.image.ImageGenerationErrorCode.BACKEND_UNAVAILABLE;
import static com.solesonic.model.image.ImageGenerationErrorCode.GENERATION_TIMEOUT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_REFERENCE_IMAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real local endpoint, because the contract is the wire: the route, the JSON or multipart
 * body, the user's key, and how each upstream failure is reported.
 */
class LiteLlmImageClientTest {

    private static final String GENERATION_MODEL = "solesonic-image (izzy-bot)";
    private static final String EDIT_MODEL = "solesonic-image-edit (izzy-bot)";
    private static final LlmApiKey LLM_API_KEY = new LlmApiKey("user-key-0001");

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private static final Pattern IMAGE_PART = Pattern.compile(
            "Content-Disposition: form-data; name=\"image\\[]\"; filename=\"[^\"]+\"");

    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>(imagesResponse(PNG_BYTES, "1024x768"));
    private final AtomicReference<Duration> responseDelay = new AtomicReference<>(Duration.ZERO);

    private HttpServer httpServer;
    private OpenAIClient openAiClient;
    private LiteLlmImageClient liteLlmImageClient;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));

            pause(responseDelay.get());

            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);

            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        httpServer.start();

        openAiClient = client("http://localhost:" + httpServer.getAddress().getPort() + "/v1", Duration.ofSeconds(5));
        liteLlmImageClient = new LiteLlmImageClient(openAiClient, GENERATION_MODEL, EDIT_MODEL);
    }

    @AfterEach
    void tearDown() {
        openAiClient.close();
        httpServer.stop(0);
    }

    @Test
    void generate_postsJsonToGenerationsWithTheUsersKeyAndDecodesTheImage() {
        GeneratedImageBytes generatedImageBytes = liteLlmImageClient.generate("a lighthouse in a storm", LLM_API_KEY);

        assertThat(requestedPath.get()).isEqualTo("/v1/images/generations");
        assertThat(authorization.get()).isEqualTo("Bearer user-key-0001");
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(requestBody.get())
                .contains("\"model\":\"" + GENERATION_MODEL + "\"")
                .contains("\"prompt\":\"a lighthouse in a storm\"")
                .contains("\"n\":1");

        assertThat(generatedImageBytes.imageData()).isEqualTo(PNG_BYTES);
        assertThat(generatedImageBytes.contentType()).isEqualTo("image/png");
        assertThat(generatedImageBytes.width()).isEqualTo(1024);
        assertThat(generatedImageBytes.height()).isEqualTo(768);
        assertThat(generatedImageBytes.model()).isEqualTo(GENERATION_MODEL);
    }

    @Test
    void edit_postsOneMultipartFilePerReferenceAsImageArray() {
        GeneratedImageBytes generatedImageBytes = liteLlmImageClient.edit("same person, on a beach",
                List.of(attachment("image/png", PNG_BYTES)), LLM_API_KEY);

        assertThat(requestedPath.get()).isEqualTo("/v1/images/edits");
        assertThat(authorization.get()).isEqualTo("Bearer user-key-0001");
        assertThat(contentType.get()).startsWith("multipart/form-data");
        assertThat(imageParts()).isEqualTo(1);
        assertThat(requestBody.get())
                .contains(new String(PNG_BYTES, StandardCharsets.ISO_8859_1))
                .contains("name=\"model\"")
                .contains(EDIT_MODEL)
                .contains("name=\"prompt\"")
                .contains("same person, on a beach");
        assertThat(generatedImageBytes.model()).isEqualTo(EDIT_MODEL);
    }

    @Test
    void edit_withTwoReferencesSendsBothFilesInOrder() {
        liteLlmImageClient.edit("p", List.of(attachment("image/png", PNG_BYTES), attachment("image/jpeg", JPEG_BYTES)),
                LLM_API_KEY);

        String body = requestBody.get();

        assertThat(imageParts()).isEqualTo(2);
        assertThat(body.indexOf(new String(PNG_BYTES, StandardCharsets.ISO_8859_1)))
                .isLessThan(body.indexOf(new String(JPEG_BYTES, StandardCharsets.ISO_8859_1)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            400 | {"error":{"message":"prompt is required","type":"invalid_request_error","param":"prompt","code":"missing_required_parameter"}} | INVALID_PROMPT
            400 | {"error":{"message":"bad image","type":"invalid_request_error","param":"image","code":"invalid_value"}}                         | INVALID_REFERENCE_IMAGE
            401 | {"error":{"message":"Authentication Error","type":"auth_error","param":null,"code":"401"}}                                      | FORBIDDEN
            403 | {"error":{"message":"key not allowed this model","type":"auth_error","param":null,"code":"403"}}                                | FORBIDDEN
            429 | {"error":{"message":"budget exceeded","type":"budget_exceeded","param":null,"code":"429"}}                                      | RATE_LIMITED
            503 | {"error":{"message":"ComfyUI is unreachable","type":"server_error","param":null,"code":"comfyui_unavailable"}}                  | BACKEND_UNAVAILABLE
            502 | {"error":{"message":"Bad gateway","type":null,"param":null,"code":"502"}}                                                       | BACKEND_UNAVAILABLE
            408 | {"error":{"message":"Request timed out","type":null,"param":null,"code":"408"}}                                                 | GENERATION_TIMEOUT
            504 | {"error":{"message":"took too long","type":"server_error","param":null,"code":"generation_timeout"}}                            | GENERATION_TIMEOUT
            500 | {"error":{"message":"ComfyUI rejected the workflow","type":"server_error","param":null,"code":"generation_failed"}}             | INTERNAL
            500 | {"error":{"message":"No usable ComfyUI workflow","type":"server_error","param":null,"code":"no_workflow"}}                      | BACKEND_UNAVAILABLE
            """)
    void generate_mapsEachUpstreamFailure(int status, String body, ImageGenerationErrorCode expected) {
        responseStatus.set(status);
        responseBody.set(body);

        assertThatThrownBy(() -> liteLlmImageClient.generate("p", LLM_API_KEY))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(expected));
    }

    /**
     * LiteLLM re-wraps the service's error, so the service's code can arrive only inside the message.
     */
    @Test
    void generate_findsTheServicesCodeInsideALiteLlmWrappedMessage() {
        responseStatus.set(500);
        responseBody.set("""
                {"error":{"message":"litellm.APIError: {'error': {'code': 'generation_timeout'}}","type":null,"param":null,"code":"500"}}""");

        assertThatThrownBy(() -> liteLlmImageClient.generate("p", LLM_API_KEY))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(GENERATION_TIMEOUT));
    }

    @Test
    void edit_aCountNoWorkflowAcceptsIsAnInvalidReferenceImageWithAClearMessage() {
        responseStatus.set(500);
        responseBody.set("""
                {"error":{"message":"No usable ComfyUI workflow","type":"server_error","param":null,"code":"no_workflow"}}""");

        assertThatThrownBy(() -> liteLlmImageClient.edit("p",
                List.of(attachment("image/png", PNG_BYTES), attachment("image/png", PNG_BYTES)), LLM_API_KEY))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException -> {
                    assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE);
                    assertThat(imageGenerationException.getMessage()).contains("No image style accepts");
                });
    }

    /**
     * LiteLLM may relay the service's refusal under a client-error status; it is still the count.
     */
    @Test
    void edit_aNoWorkflowRefusalRelayedAsABadRequestIsStillAnInvalidReferenceImage() {
        responseStatus.set(400);
        responseBody.set("""
                {"error":{"message":"litellm.BadRequestError: no_workflow","type":null,"param":null,"code":"400"}}""");

        assertThatThrownBy(() -> liteLlmImageClient.edit("p",
                List.of(attachment("image/png", PNG_BYTES), attachment("image/png", PNG_BYTES)), LLM_API_KEY))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException -> {
                    assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE);
                    assertThat(imageGenerationException.getMessage()).contains("No image style accepts");
                });
    }

    @Test
    void edit_anOversizedReferencePayloadIsAnInvalidReferenceImage() {
        responseStatus.set(413);
        responseBody.set("{\"error\":{\"message\":\"Request Entity Too Large\",\"type\":null,\"param\":null,\"code\":\"413\"}}");

        assertThatThrownBy(() -> liteLlmImageClient.edit("p", List.of(attachment("image/png", PNG_BYTES)), LLM_API_KEY))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE));
    }

    /**
     * The upstream detail can name hosts and internal behaviour; it is logged, never returned.
     */
    @Test
    void generate_neverReturnsTheUpstreamMessage() {
        responseStatus.set(500);
        responseBody.set("""
                {"error":{"message":"comfyui at 10.0.0.7:8188 exploded","type":"server_error","param":null,"code":"generation_failed"}}""");

        assertThatThrownBy(() -> liteLlmImageClient.generate("p", LLM_API_KEY))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getMessage()).doesNotContain("10.0.0.7"));
    }

    @Test
    void generate_aResponseWithoutAnImageIsTheBackendsFailure() {
        responseBody.set("{\"created\":1761000000,\"data\":[]}");

        assertThatThrownBy(() -> liteLlmImageClient.generate("p", LLM_API_KEY))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(BACKEND_UNAVAILABLE));
    }

    @Test
    void generate_withoutASizeStillReturnsTheImage() {
        responseBody.set("{\"created\":1761000000,\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG_BYTES) + "\"}]}");

        GeneratedImageBytes generatedImageBytes = liteLlmImageClient.generate("p", LLM_API_KEY);

        assertThat(generatedImageBytes.imageData()).isEqualTo(PNG_BYTES);
        assertThat(generatedImageBytes.width()).isNull();
        assertThat(generatedImageBytes.height()).isNull();
    }

    @Test
    void generate_aRefusedConnectionIsTheBackendBeingUnavailable() throws IOException {
        int closedPort;

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            closedPort = serverSocket.getLocalPort();
        }

        OpenAIClient unreachableClient = client("http://localhost:" + closedPort + "/v1", Duration.ofSeconds(5));

        try {
            LiteLlmImageClient unreachable = new LiteLlmImageClient(unreachableClient, GENERATION_MODEL, EDIT_MODEL);

            assertThatThrownBy(() -> unreachable.generate("p", LLM_API_KEY))
                    .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                            assertThat(imageGenerationException.getErrorCode()).isEqualTo(BACKEND_UNAVAILABLE));
        } finally {
            unreachableClient.close();
        }
    }

    @Test
    void generate_aReadTimeoutIsAGenerationTimeout() {
        responseDelay.set(Duration.ofSeconds(2));

        OpenAIClient impatientClient = client("http://localhost:" + httpServer.getAddress().getPort() + "/v1",
                Duration.ofMillis(300));

        try {
            LiteLlmImageClient impatient = new LiteLlmImageClient(impatientClient, GENERATION_MODEL, EDIT_MODEL);

            assertThatThrownBy(() -> impatient.generate("p", LLM_API_KEY))
                    .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                            assertThat(imageGenerationException.getErrorCode()).isEqualTo(GENERATION_TIMEOUT));
        } finally {
            impatientClient.close();
        }
    }

    private int imageParts() {
        Matcher matcher = IMAGE_PART.matcher(requestBody.get());
        int count = 0;

        while (matcher.find()) {
            count++;
        }

        return count;
    }

    private static OpenAIClient client(String baseUrl, Duration readTimeout) {
        return new ImageOpenAiConfig().imageOpenAiClient("service-key", baseUrl, readTimeout, List.of());
    }

    @SuppressWarnings("all")
    private static String imagesResponse(byte[] imageData, String size) {
        return """
                {"created":1761000000,"data":[{"b64_json":"%s"}],"output_format":"png","size":"%s"}"""
                .formatted(Base64.getEncoder().encodeToString(imageData), size);
    }

    private static ChatAttachment attachment(String attachmentContentType, byte[] fileData) {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(UUID.randomUUID());
        chatAttachment.setContentType(attachmentContentType);
        chatAttachment.setFileData(fileData);

        return chatAttachment;
    }

    private static void pause(Duration duration) {
        if (duration.isZero()) {
            return;
        }

        try {
            Thread.sleep(duration);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
