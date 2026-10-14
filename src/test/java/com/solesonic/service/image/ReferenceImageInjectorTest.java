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
               "referenceImages":{"type":"array","minItems":1,"maxItems":1,
                 "items":{"type":"object","properties":{"data":{"type":"string"},"mimeType":{"type":"string"},"description":{"type":"string"}}}}},
             "required":["prompt"],
             "additionalProperties":false}""";

    private static final String SCHEMA_WITHOUT_MAX_ITEMS = """
            {"type":"object",
             "properties":{
               "prompt":{"type":"string"},
               "referenceImages":{"type":"array","items":{"$ref":"#/$defs/ReferenceImage"}}},
             "required":["prompt","referenceImages"],
             "$defs":{"ReferenceImage":{"type":"object","properties":{"data":{"type":"string"},"mimeType":{"type":"string"}}}}}""";

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
    void referenceSlots_readsTheAdvertisedItemCountAndOtherwiseTheMaximum() {
        assertThat(referenceImageInjector.referenceSlots(SCHEMA_WITH_REFERENCES)).isEqualTo(1);
        assertThat(referenceImageInjector.referenceSlots(SCHEMA_WITHOUT_MAX_ITEMS)).isEqualTo(3);
        assertThat(referenceImageInjector.referenceSlots(SCHEMA_WITHOUT_REFERENCES)).isZero();
        assertThat(referenceImageInjector.referenceSlots(jsonMapper.readValue(SCHEMA_WITH_REFERENCES, MAP_TYPE)))
                .isEqualTo(1);
    }

    @Test
    void modelFacing_exposesOnlyADescriptionPerImage() {
        ToolDefinition modelFacing = referenceImageInjector.modelFacing(definition(SCHEMA_WITH_REFERENCES));

        Map<String, Object> schema = jsonMapper.readValue(modelFacing.inputSchema(), MAP_TYPE);
        Map<String, Object> referenceImages = map(map(schema.get("properties")).get("referenceImages"));
        Map<String, Object> items = map(referenceImages.get("items"));

        assertThat(map(schema.get("properties"))).containsOnlyKeys("prompt", "referenceImages");
        assertThat(referenceImages.get("type")).isEqualTo("array");
        assertThat(map(items.get("properties"))).containsOnlyKeys("description");
        assertThat(modelFacing.inputSchema()).doesNotContain("\"data\"").doesNotContain("mimeType");
        assertThat(schema.get("required")).isEqualTo(List.of("prompt"));
        assertThat(modelFacing.name()).isEqualTo("generate_image");
        assertThat(modelFacing.description()).startsWith("Creates an image.").contains("automatically")
                .contains("own words");
    }

    @Test
    void modelFacing_dropsTheDefinitionTheOriginalItemsReferred() {
        ToolDefinition modelFacing = referenceImageInjector.modelFacing(definition(SCHEMA_WITHOUT_MAX_ITEMS));

        Map<String, Object> schema = jsonMapper.readValue(modelFacing.inputSchema(), MAP_TYPE);

        assertThat(schema).doesNotContainKey("$defs");
        assertThat(schema.get("required")).isEqualTo(List.of("prompt"));
        assertThat(modelFacing.inputSchema()).doesNotContain("mimeType").doesNotContain("$ref");
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
        assertThat(arguments.get("referenceImages")).isEqualTo(List.of(Map.of(
                "data", Base64.getEncoder().encodeToString(PNG_BYTES),
                "mimeType", "image/png")));
        assertThat(injection.attachmentIds()).containsExactly(attachmentId);
    }

    @Test
    void inject_mergesTheModelsDescriptionsByPositionWithTheInjectedBytes() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(
                attachment(first, "image/png", PNG_BYTES),
                attachment(second, "image/jpeg", JPEG_BYTES)));

        ReferenceImageInjection injection = referenceImageInjector.inject("""
                {"prompt":"put me on a beach","referenceImages":[
                  {"description":"  a man with glasses, head and shoulders  "},
                  {"description":"a sandy beach"}]}""", 3, toolContext(first, second));

        assertThat(referenceImages(injection)).containsExactly(
                Map.of("data", Base64.getEncoder().encodeToString(PNG_BYTES), "mimeType", "image/png",
                        "description", "a man with glasses, head and shoulders"),
                Map.of("data", Base64.getEncoder().encodeToString(JPEG_BYTES), "mimeType", "image/jpeg",
                        "description", "a sandy beach"));
    }

    @Test
    void inject_fallsBackToTheVisionDescriptionThenTheDescriptionThenOmitsIt() {
        UUID visionDescribed = UUID.randomUUID();
        UUID described = UUID.randomUUID();
        UUID undescribed = UUID.randomUUID();

        ChatAttachment visionAttachment = attachment(visionDescribed, "image/png", PNG_BYTES);
        visionAttachment.setVisionDescription("a dog on a sofa");
        visionAttachment.setDescription("ignored");

        ChatAttachment describedAttachment = attachment(described, "image/png", PNG_BYTES);
        describedAttachment.setDescription("a red car");

        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(
                visionAttachment, describedAttachment, attachment(undescribed, "image/png", PNG_BYTES)));

        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\",\"referenceImages\":[{\"description\":\" \"}]}", 3,
                toolContext(visionDescribed, described, undescribed));

        List<Map<String, Object>> referenceImages = referenceImages(injection);
        assertThat(referenceImages.get(0)).containsEntry("description", "a dog on a sofa");
        assertThat(referenceImages.get(1)).containsEntry("description", "a red car");
        assertThat(referenceImages.get(2)).doesNotContainKey("description");
    }

    @Test
    void inject_dropsAnyImageDataTheModelSuppliedItself() {
        UUID attachmentId = UUID.randomUUID();
        when(chatAttachmentService.attachments(eq(userId), anySet()))
                .thenReturn(List.of(attachment(attachmentId, "image/jpeg", JPEG_BYTES)));

        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\",\"referenceImages\":[{\"data\":\"invented\",\"mimeType\":\"image/png\",\"description\":\"a cat\"}]}",
                1, toolContext(attachmentId));

        assertThat(injection.toolCallInput()).doesNotContain("invented");
        assertThat(referenceImages(injection)).containsExactly(Map.of(
                "data", Base64.getEncoder().encodeToString(JPEG_BYTES),
                "mimeType", "image/jpeg",
                "description", "a cat"));
    }

    @Test
    void inject_capsEachDescriptionAtFiveHundredCharacters() {
        UUID attachmentId = UUID.randomUUID();
        ChatAttachment chatAttachment = attachment(attachmentId, "image/png", PNG_BYTES);
        chatAttachment.setVisionDescription("x".repeat(800));
        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(List.of(chatAttachment));

        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\"}", 1, toolContext(attachmentId));

        assertThat(String.valueOf(referenceImages(injection).getFirst().get("description"))).hasSize(500);
    }

    @Test
    void inject_withoutAttachedImagesSendsNoReferenceImagesEvenWithModelDescriptions() {
        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\",\"referenceImages\":[{\"description\":\"a man\"}]}",
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
        assertThat(referenceImages(injection)).hasSize(1);
    }

    @Test
    void inject_withoutAUserInjectsNothing() {
        ReferenceImageInjection injection = referenceImageInjector.inject(
                "{\"prompt\":\"p\"}", 1, Map.of(REFERENCE_ATTACHMENT_IDS, Set.of(UUID.randomUUID())));

        assertThat(injection.attachmentIds()).isEmpty();
        verifyNoInteractions(chatAttachmentService);
    }

    @Test
    void payload_withoutModelDescriptionsUsesTheAttachmentsOwn() {
        ChatAttachment chatAttachment = attachment(UUID.randomUUID(), "image/png", PNG_BYTES);
        chatAttachment.setVisionDescription("a lighthouse");

        assertThat(referenceImageInjector.payload(List.of(chatAttachment), List.of()))
                .containsExactly(Map.of(
                        "data", Base64.getEncoder().encodeToString(PNG_BYTES),
                        "mimeType", "image/png",
                        "description", "a lighthouse"));
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
                "not json {\"referenceImages\":[{\"data\":\"invented\"}]}", 1, toolContext(UUID.randomUUID()));

        assertThat(injection.toolCallInput()).isEqualTo("{}");
        assertThat(injection.attachmentIds()).isEmpty();
    }

    private ToolDefinition definition(String inputSchema) {
        return ToolDefinition.builder()
                .name("generate_image")
                .description("Creates an image.")
                .inputSchema(inputSchema)
                .build();
    }

    private List<Map<String, Object>> referenceImages(ReferenceImageInjection injection) {
        Object referenceImages = jsonMapper.readValue(injection.toolCallInput(), MAP_TYPE).get("referenceImages");

        return jsonMapper.convertValue(referenceImages, new TypeReference<>() {
        });
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
