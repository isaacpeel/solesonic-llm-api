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
 * Hands the user's attached images to the image tool as reference images, without the model ever
 * touching their bytes.
 * <p>
 * The model cannot produce image bytes, and two megabytes of base64 must never pass through its
 * context in either direction. So the model is shown a {@code referenceImages} parameter that holds
 * only a description per image ({@link #modelFacing}), and the bytes are filled in on the way out
 * ({@link #inject}) from the attachment ids {@code PromptService} put in the tool context. The MCP
 * server uses the descriptions to choose a workflow, which is why they belong there rather than in
 * the prompt.
 * <p>
 * Everything keys off the MCP tool's own schema: a tool that does not advertise
 * {@code referenceImages} is never sent it, and the most images sent comes from the schema's
 * {@code maxItems} when it has one.
 * <p>
 * Image data, descriptions and the injected arguments are never logged.
 */
@Component
public class ReferenceImageInjector {
    private static final Logger log = LoggerFactory.getLogger(ReferenceImageInjector.class);

    /**
     * Tool context key for the image attachment ids named by the current send. Stripped before the
     * call leaves, like {@code userToken}: the tool context becomes the MCP request's {@code _meta}.
     */
    public static final String REFERENCE_ATTACHMENT_IDS = "referenceAttachmentIds";

    public static final String REFERENCE_IMAGES = "referenceImages";

    /**
     * What ComfyUI's image loader is given by the MCP server. GIF is a chat attachment type but not
     * one the image tool accepts.
     */
    static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private static final int MAX_REFERENCE_IMAGES = 3;

    /**
     * The MCP server's own cap on a description.
     */
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String MAX_ITEMS = "maxItems";
    private static final String ITEMS = "items";
    private static final String REFERENCE = "$ref";
    private static final String DEFINITIONS = "$defs";
    private static final String DEFINITIONS_PREFIX = "#/$defs/";
    private static final String DATA = "data";
    private static final String MIME_TYPE = "mimeType";
    private static final String DESCRIPTION = "description";

    private static final String MODEL_NOTE = " Images the user attached to this message are sent to this tool "
            + "automatically; never put image data in the arguments. Put the user's request in `prompt` in their "
            + "own words, without describing the attached images; describe each image in "
            + "`referenceImages[].description` instead.";

    private static final Map<String, Object> MODEL_FACING_REFERENCE_IMAGES = Map.of(
            "type", "array",
            DESCRIPTION, "One entry per image the user attached to this message, in the order attached. The images "
                    + "themselves are sent automatically; only describe them here. Omit when no image is attached.",
            ITEMS, Map.of(
                    "type", "object",
                    PROPERTIES, Map.of(DESCRIPTION, Map.of(
                            "type", "string",
                            DESCRIPTION, "A short description of what this image shows: its subjects, and whether a "
                                    + "person is the focus (for example \"a man with glasses and a beard, head and "
                                    + "shoulders\")."))));

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
     * @return the most reference images the tool takes, or zero when it advertises none
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
     * The definition the model reads: {@code referenceImages} rebuilt from scratch to hold only a
     * description per image, and a note saying the images arrive on their own. Built rather than
     * edited so that {@code data} and {@code mimeType} cannot reach the model, which would invite it
     * to invent base64.
     */
    public ToolDefinition modelFacing(ToolDefinition toolDefinition) {
        Map<String, Object> schema = jsonMapper.readValue(toolDefinition.inputSchema(), MAP_TYPE);

        if (schema.get(PROPERTIES) instanceof Map<?, ?> properties) {
            removeReferencedDefinition(schema, properties.get(REFERENCE_IMAGES));

            Map<String, Object> modelFacingProperties = new LinkedHashMap<>();
            properties.forEach((name, property) -> modelFacingProperties.put(String.valueOf(name),
                    REFERENCE_IMAGES.equals(name) ? MODEL_FACING_REFERENCE_IMAGES : property));

            schema.put(PROPERTIES, modelFacingProperties);
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

    private static void removeReferencedDefinition(Map<String, Object> schema, Object referenceImages) {
        if (!(referenceImages instanceof Map<?, ?> referenceImagesSchema)
                || !(referenceImagesSchema.get(ITEMS) instanceof Map<?, ?> items)
                || !(items.get(REFERENCE) instanceof String reference)
                || !reference.startsWith(DEFINITIONS_PREFIX)
                || !(schema.get(DEFINITIONS) instanceof Map<?, ?> definitions)) {
            return;
        }

        Map<String, Object> remaining = new LinkedHashMap<>();
        definitions.forEach((name, definition) -> {
            if (!reference.substring(DEFINITIONS_PREFIX.length()).equals(name)) {
                remaining.put(String.valueOf(name), definition);
            }
        });

        if (remaining.isEmpty()) {
            schema.remove(DEFINITIONS);
        } else {
            schema.put(DEFINITIONS, remaining);
        }
    }

    /**
     * Rewrites the model's arguments for a tool that takes reference images. The model's
     * {@code referenceImages} is read only for each entry's {@code description}, by position;
     * anything else in it is dropped. The send's own image attachments — the user's, PNG/JPEG/WebP
     * only, at most {@code slots} of them — supply the bytes.
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
            //under referenceImages. An empty call comes back from the tool as a missing prompt.
            log.warn("Could not read tool arguments to inject reference images: {}", jacksonException.getOriginalMessage());

            return ReferenceImageInjection.none(EMPTY_ARGUMENTS);
        }

        List<String> modelDescriptions = modelDescriptions(arguments.remove(REFERENCE_IMAGES));

        log.debug("The model described {} reference image(s)", modelDescriptions.size());

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

        arguments.put(REFERENCE_IMAGES, payload(attachments, modelDescriptions));

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
     * The {@code referenceImages} argument value, in the shape the MCP tool's schema declares. Each
     * image's description is the model's, by position, falling back to the attachment's vision
     * description and then its own; it is left out when there is none.
     */
    public List<Map<String, Object>> payload(List<ChatAttachment> attachments, List<String> modelDescriptions) {
        List<Map<String, Object>> referenceImages = new ArrayList<>(attachments.size());

        for (int index = 0; index < attachments.size(); index++) {
            ChatAttachment attachment = attachments.get(index);
            String modelDescription = index < modelDescriptions.size() ? modelDescriptions.get(index) : null;

            Map<String, Object> referenceImage = new LinkedHashMap<>();
            referenceImage.put(DATA, Base64.getEncoder().encodeToString(attachment.getFileData()));
            referenceImage.put(MIME_TYPE, attachment.getContentType().toLowerCase(Locale.ROOT));

            String description = description(modelDescription, attachment);

            if (description != null) {
                referenceImage.put(DESCRIPTION, description);
            }

            referenceImages.add(referenceImage);
        }

        return referenceImages;
    }

    private static String description(String modelDescription, ChatAttachment attachment) {
        String description = StringUtils.firstNonBlank(modelDescription, attachment.getVisionDescription(),
                attachment.getDescription());

        return StringUtils.truncate(StringUtils.trimToNull(description), MAX_DESCRIPTION_LENGTH);
    }

    /**
     * Each entry's {@code description}, by position, and nothing else — any other key is where
     * invented image data would be.
     */
    private static List<String> modelDescriptions(Object referenceImages) {
        if (!(referenceImages instanceof List<?> entries)) {
            return List.of();
        }

        List<String> descriptions = new ArrayList<>(entries.size());

        for (Object entry : entries) {
            descriptions.add(entry instanceof Map<?, ?> fields && fields.get(DESCRIPTION) instanceof String description
                    ? description
                    : null);
        }

        return descriptions;
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
