package com.solesonic.service.image;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.image.ReferenceImageInjection;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.service.image.ReferenceImageInjector.REFERENCE_ATTACHMENT_IDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceImageInjectorTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String SCHEMA_WITH_REFERENCES = """
            {"type":"object",
             "properties":{
               "prompt":{"type":"string"},
               "reference_images":{"type":"array","minItems":1,"maxItems":1,
                 "items":{"type":"object","properties":{"data":{"type":"string"},"mimeType":{"type":"string"}}}}},
             "required":["prompt"],
             "additionalProperties":false}""";

    private static final String SCHEMA_WITHOUT_REFERENCES = """
            {"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}""";

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private ChatAttachmentService chatAttachmentService;

    private ReferenceImageInjector referenceImageInjector;
    private UUID userId;

    @BeforeEach
    void setUp() {
        referenceImageInjector = new ReferenceImageInjector(chatAttachmentService, jsonMapper);
        userId = UUID.randomUUID();
    }

    @Test
    void referenceSlots_readsTheAdvertisedItemCount() {
        assertThat(referenceImageInjector.referenceSlots(SCHEMA_WITH_REFERENCES)).isEqualTo(1);
        assertThat(referenceImageInjector.referenceSlots(SCHEMA_WITHOUT_REFERENCES)).isZero();
        assertThat(referenceImageInjector.referenceSlots(jsonMapper.readValue(SCHEMA_WITH_REFERENCES, MAP_TYPE)))
                .isEqualTo(1);
    }

    /**
     * The model can not produce image bytes and must not try: seeing the parameter is an invitation
     * to invent base64, so it is removed from the definition the model reads.
     */
    @Test
    void modelFacing_removesReferenceImagesAndSaysTheyAreSuppliedAutomatically() {
        ToolDefinition original = ToolDefinition.builder()
                .name("generate_image")
                .description("Creates an image.")
                .inputSchema(SCHEMA_WITH_REFERENCES)
                .build();

        ToolDefinition modelFacing = referenceImageInjector.modelFacing(original);

        Map<String, Object> schema = jsonMapper.readValue(modelFacing.inputSchema(), MAP_TYPE);
        assertThat(map(schema.get("properties"))).containsOnlyKeys("prompt");
        assertThat(schema.get("required")).isEqualTo(List.of("prompt"));
        assertThat(modelFacing.name()).isEqualTo("generate_image");
        assertThat(modelFacing.description()).startsWith("Creates an image.").contains("automatically");
    }

    @Test
    void inject_addsTheUsersAttachedImagesAsBase64() {
        UUID attachmentId = UUID.randomUUID();
        when(chatAttachmentService.attachments(eq(userId), anySet()))
                .thenReturn(List.of(attachment(attachmentId, "image/png", PNG_BYTES)));

        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"a watercolor\"}", 1, toolContext(attachmentId));

        Map<String, Object> arguments = jsonMapper.readValue(injection.toolCallInput(), MAP_TYPE);
        assertThat(arguments).containsEntry("prompt", "a watercolor");
        assertThat(arguments.get("reference_images")).isEqualTo(List.of(Map.of(
                "data", Base64.getEncoder().encodeToString(PNG_BYTES),
                "mimeType", "image/png")));
        assertThat(injection.attachmentIds()).containsExactly(attachmentId);
    }

    /**
     * Whatever the model put there is discarded: only the server supplies image data.
     */
    @Test
    void inject_replacesAnythingTheModelSuppliedItself() {
        UUID attachmentId = UUID.randomUUID();
        when(chatAttachmentService.attachments(eq(userId), anySet()))
                .thenReturn(List.of(attachment(attachmentId, "image/jpeg", JPEG_BYTES)));

        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\",\"reference_images\":[{\"data\":\"invented\",\"mimeType\":\"image/png\"}]}",
                1, toolContext(attachmentId));

        assertThat(injection.toolCallInput()).doesNotContain("invented").contains("image/jpeg");
    }

    @Test
    void inject_withoutAttachedImagesStripsAModelSuppliedValueAndInjectsNothing() {
        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\",\"reference_images\":[{\"data\":\"invented\",\"mimeType\":\"image/png\"}]}",
                1, Map.of(USER_ID, userId));

        assertThat(jsonMapper.readValue(injection.toolCallInput(), MAP_TYPE)).containsOnlyKeys("prompt");
        assertThat(injection.attachmentIds()).isEmpty();
        verifyNoInteractions(chatAttachmentService);
    }

    /**
     * ComfyUI accepts PNG, JPEG and WebP only, and a document is never an image no matter what id
     * it arrived under. Ownership is the repository's {@code where} clause, so another user's id
     * simply never comes back.
     */
    @Test
    void inject_skipsGifsDocumentsAndAnythingTheUserDoesNotOwn() {
        UUID gifId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID someoneElsesId = UUID.randomUUID();

        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(
                attachment(gifId, "image/gif", new byte[]{1}),
                attachment(documentId, "application/pdf", new byte[]{2})));

        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\"}", 1, toolContext(gifId, documentId, someoneElsesId));

        assertThat(jsonMapper.readValue(injection.toolCallInput(), MAP_TYPE)).containsOnlyKeys("prompt");
        assertThat(injection.attachmentIds()).isEmpty();
    }

    @Test
    void inject_capsAtTheAdvertisedSlotCount() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(
                attachment(first, "image/png", PNG_BYTES),
                attachment(second, "image/png", PNG_BYTES)));

        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\"}", 1, toolContext(first, second));

        assertThat(injection.attachmentIds()).containsExactly(first);
        Object referenceImages = jsonMapper.readValue(injection.toolCallInput(), MAP_TYPE).get("reference_images");
        assertThat(referenceImages).isInstanceOf(List.class);
        assertThat((List<?>) referenceImages).hasSize(1);
    }

    @Test
    void inject_withoutAUserInjectsNothing() {
        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\"}", 1, Map.of(REFERENCE_ATTACHMENT_IDS, Set.of(UUID.randomUUID())));

        assertThat(injection.attachmentIds()).isEmpty();
        verifyNoInteractions(chatAttachmentService);
    }

    @Test
    void usableAttachments_keepsOnlySupportedImagesInOrder() {
        UUID png = UUID.randomUUID();
        UUID gif = UUID.randomUUID();
        when(chatAttachmentService.attachments(any(), anySet())).thenReturn(List.of(
                attachment(png, "image/png", PNG_BYTES),
                attachment(gif, "image/gif", new byte[]{1})));

        assertThat(referenceImageInjector.usableAttachments(userId, List.of(png, gif)))
                .extracting(ChatAttachment::getId)
                .containsExactly(png);
    }

    /**
     * Image 1 fills slot 1, so the send's order decides, not when each attachment was staged.
     */
    @Test
    void usableAttachments_followsTheRequestedOrderNotCreationOrder() {
        UUID stagedFirst = UUID.randomUUID();
        UUID stagedSecond = UUID.randomUUID();
        when(chatAttachmentService.attachments(any(), anySet())).thenReturn(List.of(
                attachment(stagedFirst, "image/png", PNG_BYTES),
                attachment(stagedSecond, "image/png", PNG_BYTES)));

        assertThat(referenceImageInjector.usableAttachments(userId, List.of(stagedSecond, stagedFirst)))
                .extracting(ChatAttachment::getId)
                .containsExactly(stagedSecond, stagedFirst);
    }

    @Test
    void inject_withUnreadableArgumentsFailsClosed() {
        ReferenceImageInjection injection = referenceImageInjector.inject(
                "not json {\"reference_images\":[{\"data\":\"invented\"}]}", 1, toolContext(UUID.randomUUID()));

        assertThat(injection.toolCallInput()).isEqualTo("{}");
        assertThat(injection.attachmentIds()).isEmpty();
    }

    private Map<String, Object> toolContext(UUID... attachmentIds) {
        return Map.of(USER_ID, userId, REFERENCE_ATTACHMENT_IDS, new LinkedHashSet<>(List.of(attachmentIds)));
    }

    private ChatAttachment attachment(UUID attachmentId, String contentType, byte[] fileData) {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(attachmentId);
        chatAttachment.setUserId(userId);
        chatAttachment.setContentType(contentType);
        chatAttachment.setFileData(fileData);

        return chatAttachment;
    }

    private Map<String, Object> map(Object value) {
        return jsonMapper.convertValue(value, MAP_TYPE);
    }
}
