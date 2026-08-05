package com.solesonic.service.image;

import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.model.image.ImageGenerationMetadata;
import com.solesonic.service.chat.events.NotificationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.util.JsonHelper;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.service.prompt.PromptService.CHAT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GeneratedImageToolInterceptorTest {

    private static final String METADATA_TEXT = """
            Generated with FLUX.1-schnell.
            Size: 1024x1024
            Steps: 4
            Seed: 8339331079448168597
            Elapsed: 8.2s""";

    private static final byte[] IMAGE_BYTES = "not really a png, but bytes all the same".getBytes();

    private static final String TOOL_CALL_INPUT = """
            {"prompt":"a small red lighthouse"}""";

    @Mock
    private GeneratedImageService generatedImageService;

    @Mock
    private NotificationService notificationService;

    private GeneratedImageToolInterceptor generatedImageToolInterceptor;

    private UUID chatId;
    private UUID userId;
    private UUID imageId;

    @BeforeEach
    void setUp() {
        generatedImageToolInterceptor = new GeneratedImageToolInterceptor(
                generatedImageService, notificationService, JsonMapper.builder().build());

        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();
        imageId = UUID.randomUUID();

        when(generatedImageService.store(any(), any(), any(), any(), any(), any()))
                .thenReturn(summary(imageId));
    }

    @Test
    void handlesTheImageToolUnderItsPlainAndPrefixedNames() {
        assertThat(generatedImageToolInterceptor.handles("generate_image")).isTrue();
        assertThat(generatedImageToolInterceptor.handles("solesonic_generate_image")).isTrue();
        assertThat(generatedImageToolInterceptor.handles("create_jira")).isFalse();
        assertThat(generatedImageToolInterceptor.handles("generate_image_preview")).isFalse();
    }

    /**
     * The regression this whole class exists to prevent. Roughly 2MB of base64 is on the order of
     * half a million tokens; whatever the model is handed, it must not contain the image data.
     */
    @Test
    void interceptKeepsBase64OutOfWhatTheModelSees() {
        String base64 = Base64.getEncoder().encodeToString(IMAGE_BYTES);

        String modelText = generatedImageToolInterceptor
                .intercept(TOOL_CALL_INPUT, rawResult(base64), toolContext());

        assertThat(modelText).doesNotContain(base64);
        assertThat(modelText).contains(imageId.toString());
        assertThat(modelText).contains("8339331079448168597");
    }

    /**
     * A model told only that an image exists will describe it anyway — which is how a lighthouse
     * prompt produced several hundred words about a cyberpunk cityscape.
     */
    @Test
    void interceptTellsTheModelItCannotSeeTheImage() {
        String modelText = generatedImageToolInterceptor
                .intercept(TOOL_CALL_INPUT, rawResult(base64()), toolContext());

        assertThat(modelText).contains("cannot see this image");
        assertThat(modelText).contains("Do not describe");
    }

    @Test
    void interceptStoresTheDecodedImageAgainstTheChatAndTheUser() {
        generatedImageToolInterceptor.intercept(TOOL_CALL_INPUT, rawResult(base64()), toolContext());

        ArgumentCaptor<byte[]> imageDataCaptor = ArgumentCaptor.forClass(byte[].class);

        verify(generatedImageService).store(eq(userId), eq(chatId), eq("a small red lighthouse"),
                imageDataCaptor.capture(), eq("image/png"), any(ImageGenerationMetadata.class));

        assertThat(imageDataCaptor.getValue()).isEqualTo(IMAGE_BYTES);
    }

    @Test
    void interceptAnnouncesTheImageOnTheChatStream() {
        generatedImageToolInterceptor.intercept(TOOL_CALL_INPUT, rawResult(base64()), toolContext());

        verify(notificationService).emitGeneratedImage(eq(chatId), any(GeneratedImageSummary.class));
    }

    /**
     * Explicit generation has no chat to announce into, but the image is still stored.
     */
    @Test
    void interceptStoresWithoutAChatAndAnnouncesNothing() {
        generatedImageToolInterceptor.intercept(TOOL_CALL_INPUT, rawResult(base64()),
                Map.of(USER_ID, userId));

        verify(generatedImageService).store(eq(userId), isNull(), any(), any(), any(), any());
        verify(notificationService, never()).emitGeneratedImage(any(), any());
    }

    /**
     * Every read of an image is user-scoped, so one stored without an owner could never be served
     * back. Dropping it beats leaking bytes nobody can reach.
     */
    @Test
    void interceptDiscardsAnImageItCannotAttribute() {
        String modelText = generatedImageToolInterceptor.intercept(TOOL_CALL_INPUT,
                rawResult(base64()), Map.of(CHAT_ID, chatId));

        verifyNoInteractions(generatedImageService);
        verifyNoInteractions(notificationService);

        assertThat(modelText).isEqualTo(METADATA_TEXT);
        assertThat(modelText).doesNotContain(base64());
    }

    /**
     * A result the interceptor cannot parse must still not pass the raw payload through — that is
     * exactly the case where the payload is huge and unexpected.
     */
    @Test
    void interceptWithholdsAResultItCannotRead() {
        String modelText = generatedImageToolInterceptor
                .intercept(TOOL_CALL_INPUT, "this is not json " + base64(), toolContext());

        assertThat(modelText).doesNotContain(base64());
        verifyNoInteractions(generatedImageService);
    }

    /**
     * The shape that actually arrives, built by serialising real {@code McpSchema} content the same
     * way {@code SyncMcpToolCallback} does — which loses the {@code type} discriminator. Keying on
     * that field made every real generation look like a result with no image in it.
     */
    @Test
    void interceptFindsTheImageInARealSpringAiSerialisedResult() {
        String rawResult = new JsonHelper().toJson(List.of(
                new McpSchema.ImageContent(null, base64(), "image/png", null),
                new McpSchema.TextContent(null, METADATA_TEXT, null)));

        assertThat(rawResult).doesNotContain("\"type\"");

        String modelText = generatedImageToolInterceptor
                .intercept(TOOL_CALL_INPUT, rawResult, toolContext());

        verify(generatedImageService).store(eq(userId), eq(chatId), any(), any(), eq("image/png"), any());

        assertThat(modelText).doesNotContain(base64());
        assertThat(modelText).contains(imageId.toString());
    }

    @Test
    void interceptPassesTextThroughWhenTheResultCarriesNoImage() {
        String rawResult = """
                [{"type":"text","text":"%s"}]""".formatted(METADATA_TEXT.replace("\n", "\\n"));

        String modelText = generatedImageToolInterceptor
                .intercept(TOOL_CALL_INPUT, rawResult, toolContext());

        assertThat(modelText).isEqualTo(METADATA_TEXT);
        verifyNoInteractions(generatedImageService);
    }

    private Map<String, Object> toolContext() {
        return Map.of(CHAT_ID, chatId, USER_ID, userId);
    }

    private static String base64() {
        return Base64.getEncoder().encodeToString(IMAGE_BYTES);
    }

    private static String rawResult(String base64) {
        return """
                [{"type":"image","data":"%s","mimeType":"image/png"},{"type":"text","text":"%s"}]"""
                .formatted(base64, METADATA_TEXT.replace("\n", "\\n"));
    }

    private GeneratedImageSummary summary(UUID imageId) {
        return new GeneratedImageSummary(imageId, null, "/izzybot/images/" + imageId,
                "a small red lighthouse", "FLUX.1-schnell", 8339331079448168597L,
                1024, 1024, 4, 8.2d, IMAGE_BYTES.length, ZonedDateTime.now());
    }
}
