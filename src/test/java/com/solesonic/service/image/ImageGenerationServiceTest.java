package com.solesonic.service.image;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.model.image.ImageGenerationErrorCode.BACKEND_UNAVAILABLE;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_REFERENCE_IMAGE;
import static com.solesonic.model.image.ImageGenerationErrorCode.NO_IMAGE_TOOLS;
import static com.solesonic.model.image.ImageGenerationErrorCode.REFERENCE_IMAGES_UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageGenerationServiceTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static final Map<String, Object> REFERENCE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "prompt", Map.of("type", "string"),
                    "referenceImages", Map.of("type", "array")));

    private static final Map<String, Object> PROMPT_ONLY_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("prompt", Map.of("type", "string")));

    @Mock
    private McpSyncClient mcpSyncClient;

    @Mock
    private GeneratedImageService generatedImageService;

    @Mock
    private ImageGenerationProgressBroker imageGenerationProgressBroker;

    @Mock
    private ImageToolCatalog imageToolCatalog;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    private ImageGenerationService imageGenerationService;
    private UUID userId;
    private UUID attachmentId;

    @BeforeEach
    void setUp() {
        ReferenceImageInjector referenceImageInjector =
                new ReferenceImageInjector(chatAttachmentService, JsonMapper.builder().build());

        imageGenerationService = new ImageGenerationService(mcpSyncClient, generatedImageService,
                imageGenerationProgressBroker, imageToolCatalog, referenceImageInjector, 1, Duration.ofSeconds(1));

        userId = UUID.randomUUID();
        attachmentId = UUID.randomUUID();

        when(imageToolCatalog.resolve()).thenReturn(tool(REFERENCE_SCHEMA));
        when(mcpSyncClient.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addContent(McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(PNG_BYTES), "image/png").build())
                .addTextContent("Generated with FLUX.\nSize: 1024x1024\nSeed: 42\nElapsed: 1.0s")
                .addTextContent("Workflow: flux_schnell. Why: a new image was requested.")
                .build());
        when(generatedImageService.store(any(), any(), any(), any(), any(), any(), any())).thenReturn(summary());
    }

    @Test
    void generate_withoutReferences_sendsOnlyThePromptToGenerateImage() {
        imageGenerationService.generate("a lighthouse", List.of(), userId, "token");

        assertThat(callToolRequest().name()).isEqualTo("generate_image");
        assertThat(callToolRequest().arguments()).containsOnlyKeys("prompt");
        verify(generatedImageService).store(eq(userId), any(), eq("a lighthouse"), any(), any(), any(), eq(List.of()));
    }

    @Test
    void generate_withoutTheImageTool_isRefusedBeforeTheCall() {
        when(imageToolCatalog.resolve()).thenThrow(new ImageGenerationException(NO_IMAGE_TOOLS, "not configured"));

        assertThatThrownBy(() -> imageGenerationService.generate("p", List.of(), userId, "token"))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(NO_IMAGE_TOOLS));

        verify(mcpSyncClient, never()).callTool(any());
    }

    @Test
    void generate_withOneReferenceAgainstASchemaWithoutMaxItems_sendsTheBytesAndTheDescription() {
        ChatAttachment chatAttachment = attachment();
        chatAttachment.setVisionDescription("a man with glasses, head and shoulders");
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(chatAttachment));

        imageGenerationService.generate("put me on a beach", List.of(attachmentId), userId, "token");

        assertThat(callToolRequest().arguments().get("referenceImages")).isEqualTo(List.of(Map.of(
                "data", Base64.getEncoder().encodeToString(PNG_BYTES),
                "mimeType", "image/png",
                "description", "a man with glasses, head and shoulders")));
        verify(generatedImageService).store(eq(userId), any(), eq("put me on a beach"), any(), any(), any(),
                eq(List.of(attachmentId)));
    }

    @Test
    void generate_withMoreThanThreeReferences_isRefusedBeforeTheCall() {
        List<UUID> fourAttachments = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> imageGenerationService.generate("p", fourAttachments, userId, "token"))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException -> {
                    assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE);
                    assertThat(imageGenerationException.getMessage()).contains("At most 3");
                });

        verify(mcpSyncClient, never()).callTool(any());
    }

    /**
     * An explicit request for references is a request, not a hint: generating without them would
     * hand back an image the caller did not ask for.
     */
    @Test
    void generate_withReferencesAgainstAToolThatTakesNone_isRefusedBeforeTheCall() {
        when(imageToolCatalog.resolve()).thenReturn(tool(PROMPT_ONLY_SCHEMA));

        assertThatThrownBy(() -> imageGenerationService.generate("p", List.of(attachmentId), userId, "token"))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(REFERENCE_IMAGES_UNSUPPORTED));

        verify(mcpSyncClient, never()).callTool(any());
    }

    @Test
    void generate_withAReferenceTheCallerCannotUse_isRefusedBeforeTheCall() {
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of());

        assertThatThrownBy(() -> imageGenerationService.generate("p", List.of(attachmentId), userId, "token"))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE));

        verify(mcpSyncClient, never()).callTool(any());
    }

    @Test
    void generate_aCountNoWorkflowAccepts_isReportedClearly() {
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(attachment()));
        when(mcpSyncClient.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("No image workflow accepts 1 reference image(s).")
                .isError(true)
                .build());

        assertThatThrownBy(() -> imageGenerationService.generate("p", List.of(attachmentId), userId, "token"))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException -> {
                    assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE);
                    assertThat(imageGenerationException.getMessage()).contains("No image style accepts");
                });
    }

    @Test
    void generate_aReferenceTheToolRejects_isReportedAsAnInvalidReferenceImage() {
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(attachment()));
        when(mcpSyncClient.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("Reference image 1 is larger than the 10MB limit.")
                .isError(true)
                .build());

        assertThatThrownBy(() -> imageGenerationService.generate("p", List.of(attachmentId), userId, "token"))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(INVALID_REFERENCE_IMAGE));
    }

    /**
     * A ComfyUI outage during the upload is the backend's failure, not the caller's image.
     */
    @Test
    void generate_aFailedComfyUiUpload_isReportedAsTheBackendNotTheImage() {
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(attachment()));
        when(mcpSyncClient.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("ComfyUI image upload failed with status 503 SERVICE_UNAVAILABLE")
                .isError(true)
                .build());

        assertThatThrownBy(() -> imageGenerationService.generate("p", List.of(attachmentId), userId, "token"))
                .isInstanceOfSatisfying(ImageGenerationException.class, imageGenerationException ->
                        assertThat(imageGenerationException.getErrorCode()).isEqualTo(BACKEND_UNAVAILABLE));
    }

    private McpSchema.CallToolRequest callToolRequest() {
        ArgumentCaptor<McpSchema.CallToolRequest> captor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpSyncClient).callTool(captor.capture());

        return captor.getValue();
    }

    private McpSchema.Tool tool(Map<String, Object> inputSchema) {
        return McpSchema.Tool.builder("generate_image", inputSchema).build();
    }

    private ChatAttachment attachment() {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(attachmentId);
        chatAttachment.setUserId(userId);
        chatAttachment.setContentType("image/png");
        chatAttachment.setFileData(PNG_BYTES);

        return chatAttachment;
    }

    private GeneratedImageSummary summary() {
        return new GeneratedImageSummary(UUID.randomUUID(), userId, null, "/images/x", null, "p", "FLUX", 42L,
                1024, 1024, null, 1.0, PNG_BYTES.length, ZonedDateTime.now(), List.of());
    }
}
