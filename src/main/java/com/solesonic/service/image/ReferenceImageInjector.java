package com.solesonic.service.image;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.image.ReferenceImageInjection;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;

/**
 * Hands the user's attached images to an image tool as reference images, without the model ever
 * touching them.
 * <p>
 * The model cannot produce image bytes, and two megabytes of base64 must never pass through its
 * context in either direction. So the tool's {@code reference_images} parameter is hidden from the
 * model ({@link #modelFacing}) and filled in server-side on the way out ({@link #inject}) from the
 * attachment ids {@code PromptService} put in the tool context. Whatever the model wrote there
 * itself is discarded.
 * <p>
 * Everything keys off the MCP tool's own schema: a tool that does not advertise
 * {@code reference_images} is never sent it, so an MCP server that predates the parameter keeps
 * working, and the slot count comes from the schema's {@code maxItems} rather than being guessed.
 * <p>
 * Image data and the injected arguments are never logged.
 */
@Component
public class ReferenceImageInjector {
    private static final Logger log = LoggerFactory.getLogger(ReferenceImageInjector.class);

    /**
     * Tool context key for the image attachment ids named by the current send. Stripped before the
     * call leaves, like {@code userToken}: the tool context becomes the MCP request's {@code _meta}.
     */
    public static final String REFERENCE_ATTACHMENT_IDS = "referenceAttachmentIds";

    public static final String REFERENCE_IMAGES = "reference_images";

    /**
     * What ComfyUI's image loader is given by the MCP server. GIF is a chat attachment type but not
     * one the image tool accepts.
     */
    static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private static final int MAX_REFERENCE_IMAGES = 3;

    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String MAX_ITEMS = "maxItems";
    private static final String DATA = "data";
    private static final String MIME_TYPE = "mimeType";

    private static final String MODEL_NOTE = " Images the user attached to this message are passed to this "
            + "tool automatically as reference images; never put image data in the arguments.";

    private static final String EMPTY_ARGUMENTS = "{}";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatAttachmentService chatAttachmentService;
    private final JsonMapper jsonMapper;

    public ReferenceImageInjector(ChatAttachmentService chatAttachmentService, JsonMapper jsonMapper) {
        this.chatAttachmentService = chatAttachmentService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @return how many reference images the tool takes, or zero when it advertises none
     */
    public int referenceSlots(String inputSchema) {
        if (StringUtils.isBlank(inputSchema)) {
            return 0;
        }

        try {
            return referenceSlots(jsonMapper.readValue(inputSchema, MAP_TYPE));
        } catch (JacksonException jacksonException) {
            log.warn("Could not read a tool input schema: {}", jacksonException.getOriginalMessage());

            return 0;
        }
    }

    public int referenceSlots(Map<String, Object> inputSchema) {
        if (inputSchema == null
                || !(inputSchema.get(PROPERTIES) instanceof Map<?, ?> properties)
                || !(properties.get(REFERENCE_IMAGES) instanceof Map<?, ?> referenceImages)) {
            return 0;
        }

        if (referenceImages.get(MAX_ITEMS) instanceof Number maxItems) {
            return Math.clamp(maxItems.longValue(), 0, MAX_REFERENCE_IMAGES);
        }

        return MAX_REFERENCE_IMAGES;
    }

    /**
     * The definition the model reads: {@code reference_images} removed, and one sentence saying the
     * images arrive on their own, so the model neither invents base64 nor refuses an edit because it
     * thinks it cannot see the attachment.
     */
    public ToolDefinition modelFacing(ToolDefinition toolDefinition) {
        Map<String, Object> schema = jsonMapper.readValue(toolDefinition.inputSchema(), MAP_TYPE);

        if (schema.get(PROPERTIES) instanceof Map<?, ?> properties) {
            Map<String, Object> remaining = new LinkedHashMap<>();
            properties.forEach((name, property) -> {
                if (!REFERENCE_IMAGES.equals(name)) {
                    remaining.put(String.valueOf(name), property);
                }
            });

            schema.put(PROPERTIES, remaining);
        }

        if (schema.get(REQUIRED) instanceof List<?> required) {
            schema.put(REQUIRED, required.stream().filter(name -> !REFERENCE_IMAGES.equals(name)).toList());
        }

        return ToolDefinition.builder()
                .name(toolDefinition.name())
                .description(StringUtils.defaultString(toolDefinition.description()) + MODEL_NOTE)
                .inputSchema(jsonMapper.writeValueAsString(schema))
                .build();
    }

    /**
     * Rewrites the model's arguments for a tool that takes reference images: anything the model put
     * under {@code reference_images} is dropped, and the send's own image attachments — the user's,
     * PNG/JPEG/WebP only, at most {@code slots} of them — take its place.
     * <p>
     * An attachment that does not resolve is skipped rather than failing the turn; if none resolve,
     * the tool is called without references.
     */
    public ReferenceImageInjection inject(String toolCallInput, int slots, Map<String, Object> toolContext) {
        Map<String, Object> arguments;

        try {
            arguments = StringUtils.isBlank(toolCallInput) ? new LinkedHashMap<>() : jsonMapper.readValue(toolCallInput, MAP_TYPE);
        } catch (JacksonException jacksonException) {
            //Fails closed: passing the raw input on would also pass on whatever the model wrote
            //under reference_images. An empty call comes back from the tool as a missing prompt.
            log.warn("Could not read tool arguments to inject reference images: {}", jacksonException.getOriginalMessage());

            return ReferenceImageInjection.none(EMPTY_ARGUMENTS);
        }

        if (arguments.remove(REFERENCE_IMAGES) != null) {
            log.warn("Discarded reference_images the model supplied itself");
        }

        List<ChatAttachment> attachments = usableAttachments(uuid(toolContext.get(USER_ID)),
                attachmentIds(toolContext.get(REFERENCE_ATTACHMENT_IDS)));

        if (attachments.size() > slots) {
            log.info("{} usable image attachment(s) but the tool takes {}; sending the first {}",
                    attachments.size(), slots, slots);

            attachments = attachments.subList(0, slots);
        }

        if (attachments.isEmpty()) {
            return new ReferenceImageInjection(jsonMapper.writeValueAsString(arguments), List.of());
        }

        arguments.put(REFERENCE_IMAGES, payload(attachments));

        List<UUID> attachmentIds = attachments.stream().map(ChatAttachment::getId).toList();

        log.info("Injected {} reference image(s) into the tool call", attachmentIds.size());
        log.debug("Reference attachments injected: {}", attachmentIds);

        return new ReferenceImageInjection(jsonMapper.writeValueAsString(arguments), attachmentIds);
    }

    /**
     * The named attachments that can be sent as reference images, in the order they were named —
     * slot order is meaningful to the tool, and the repository returns rows by creation time.
     * Ownership is enforced by the query, so an id belonging to someone else simply does not come
     * back.
     */
    public List<ChatAttachment> usableAttachments(UUID userId, Collection<UUID> attachmentIds) {
        if (userId == null || attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }

        List<UUID> requested = List.copyOf(new LinkedHashSet<>(attachmentIds));

        return chatAttachmentService.attachments(userId, new LinkedHashSet<>(requested)).stream()
                .filter(ReferenceImageInjector::isUsable)
                .sorted(Comparator.comparingInt(attachment -> requested.indexOf(attachment.getId())))
                .toList();
    }

    /**
     * The {@code reference_images} argument value, in the shape the MCP tool's schema declares.
     */
    public List<Map<String, Object>> payload(List<ChatAttachment> attachments) {
        List<Map<String, Object>> referenceImages = new ArrayList<>(attachments.size());

        for (ChatAttachment attachment : attachments) {
            Map<String, Object> referenceImage = new LinkedHashMap<>();
            referenceImage.put(DATA, Base64.getEncoder().encodeToString(attachment.getFileData()));
            referenceImage.put(MIME_TYPE, attachment.getContentType().toLowerCase(Locale.ROOT));

            referenceImages.add(referenceImage);
        }

        return referenceImages;
    }

    private static boolean isUsable(ChatAttachment attachment) {
        return attachment.getContentType() != null
                && SUPPORTED_CONTENT_TYPES.contains(attachment.getContentType().toLowerCase(Locale.ROOT))
                && attachment.getFileData() != null
                && attachment.getFileData().length > 0;
    }

    private static List<UUID> attachmentIds(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }

        return values.stream()
                .map(ReferenceImageInjector::uuid)
                .filter(Objects::nonNull)
                .toList();
    }

    private static UUID uuid(Object value) {
        return switch (value) {
            case UUID uuidValue -> uuidValue;
            case String stringValue -> parse(stringValue);
            case null, default -> null;
        };
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException illegalArgumentException) {
            return null;
        }
    }
}
