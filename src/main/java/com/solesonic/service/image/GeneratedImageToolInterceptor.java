package com.solesonic.service.image;

import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.model.image.ImageGenerationMetadata;
import com.solesonic.service.chat.events.NotificationService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.service.image.ImageGenerationService.GENERATE_IMAGE_TOOL;
import static com.solesonic.service.prompt.PromptService.CHAT_ID;

/**
 * Takes the image out of a {@code generate_image} tool result before that result goes back to the
 * model, stores it, and puts a short reference in its place.
 * <p>
 * <strong>This is the guard the whole feature rests on.</strong> Spring AI's MCP tool callback
 * returns the tool's content list serialised as JSON, base64 image data included, and a tool result
 * is fed straight back into the conversation as the tool response message. For this tool that is
 * roughly 2MB of base64 — on the order of half a million tokens. It does not degrade gracefully: it
 * blows the context window, and what the model actually receives is a truncated fragment of an
 * image it then describes from imagination.
 * <p>
 * It is deliberately tool-specific rather than a generic "truncate large tool results" guard.
 * Truncating base64 yields a corrupt image and a confusing failure; the image has to be recognised,
 * removed whole, and replaced with something the model can reason about.
 * <p>
 * What the model gets instead says plainly that the image exists, that the user can already see it,
 * and that the model cannot — the last part matters, because a model told only that an image was
 * generated will describe one anyway.
 */
@Component
public class GeneratedImageToolInterceptor {
    private static final Logger log = LoggerFactory.getLogger(GeneratedImageToolInterceptor.class);

    private static final TypeReference<List<Map<String, Object>>> CONTENT_LIST_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
    };

    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String IMAGE = "image";
    private static final String DATA = "data";
    private static final String MIME_TYPE = "mimeType";
    private static final String PROMPT = "prompt";
    private static final String IMAGE_MIME_PREFIX = "image/";
    private static final String DEFAULT_CONTENT_TYPE = "image/png";

    private final GeneratedImageService generatedImageService;
    private final NotificationService notificationService;
    private final JsonMapper jsonMapper;

    public GeneratedImageToolInterceptor(GeneratedImageService generatedImageService,
                                         NotificationService notificationService,
                                         JsonMapper jsonMapper) {
        this.generatedImageService = generatedImageService;
        this.notificationService = notificationService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * {@code generate_image} as a whole underscore-delimited word: Spring AI may prefix MCP tool
     * names with the client name, and the MCP server registers one tool per workflow row as
     * {@code generate_image_<workflow>}. Every one of them returns base64, so every one is guarded.
     */
    private static final Pattern IMAGE_TOOL_NAME = Pattern.compile("(^|_)" + GENERATE_IMAGE_TOOL + "(_|$)");

    /**
     * The MCP server tags every image tool with {@code _meta: {"solesonic/kind": "image-generation"}}.
     * The tag is authoritative; the name match in {@link #handles} remains only for an MCP server
     * that predates it.
     */
    public static final String META_KIND = "solesonic/kind";
    public static final String META_KIND_IMAGE_GENERATION = "image-generation";

    public static boolean isTaggedImageTool(McpSchema.Tool tool) {
        return tool != null
                && tool.meta() != null
                && META_KIND_IMAGE_GENERATION.equals(tool.meta().get(META_KIND));
    }

    public boolean handles(String toolName) {
        return toolName != null && IMAGE_TOOL_NAME.matcher(toolName).find();
    }

    public String intercept(String toolCallInput, String rawResult, Map<String, Object> toolContext) {
        return intercept(toolCallInput, rawResult, toolContext, List.of());
    }

    /**
     * @param toolCallInput          the arguments the model called the tool with; the prompt is read
     *                               out of them and stored as the image's provenance and {@code alt}
     *                               text
     * @param rawResult              the tool's content list as JSON — the thing that must not reach
     *                               the model
     * @param toolContext            carries {@code chatId} and {@code userId}; without a user the
     *                               image cannot be attributed, and is discarded rather than stored
     *                               unowned
     * @param referenceAttachmentIds the chat attachments injected as reference images, recorded
     *                               on the stored image
     * @return the text the model sees in place of the image
     */
    public String intercept(String toolCallInput,
                            String rawResult,
                            Map<String, Object> toolContext,
                            List<UUID> referenceAttachmentIds) {
        List<Map<String, Object>> content;

        try {
            content = jsonMapper.readValue(rawResult, CONTENT_LIST_TYPE);
        } catch (RuntimeException runtimeException) {
            //Length rather than content: logging the result would put the base64 in the log file.
            log.error("Could not read the generate_image result ({} chars); withholding it from the model",
                    rawResult.length(), runtimeException);

            return "The image could not be read after it was generated.";
        }

        String metadataText = content.stream()
                .filter(GeneratedImageToolInterceptor::isText)
                .map(entry -> String.valueOf(entry.get(TEXT)))
                .collect(Collectors.joining(System.lineSeparator()));

        Map<String, Object> imageContent = content.stream()
                .filter(GeneratedImageToolInterceptor::isImage)
                .findFirst()
                .orElse(null);

        if (imageContent == null) {
            log.warn("A generate_image result carried no image content");

            return metadataText;
        }

        UUID chatId = uuid(toolContext.get(CHAT_ID));
        UUID userId = uuid(toolContext.get(USER_ID));

        //The image is dropped rather than stored unattributed. An image nobody owns cannot be
        //served back — every read is user-scoped — so storing one would only leak bytes.
        if (userId == null) {
            log.error("A generate_image result arrived with no user in the tool context; discarding the image");

            return metadataText;
        }

        GeneratedImageSummary generatedImageSummary =
                store(userId, chatId, prompt(toolCallInput), imageContent, metadataText, referenceAttachmentIds);

        if (generatedImageSummary == null) {
            return "The image was generated but could not be stored, so it cannot be shown.";
        }

        //Emitted before the model has written a word, so it reaches the client well ahead of the
        //RUN_FINISHED frame that closes the turn.
        if (chatId != null) {
            notificationService.emitGeneratedImage(chatId, generatedImageSummary);
        }

        return modelText(generatedImageSummary, metadataText);
    }

    private GeneratedImageSummary store(UUID userId,
                                        UUID chatId,
                                        String userPrompt,
                                        Map<String, Object> imageContent,
                                        String metadataText,
                                        List<UUID> referenceAttachmentIds) {
        try {
            byte[] imageData = Base64.getDecoder().decode(String.valueOf(imageContent.get(DATA)));

            String contentType = StringUtils.defaultIfBlank(
                    (String) imageContent.get(MIME_TYPE), DEFAULT_CONTENT_TYPE);

            return generatedImageService.store(userId, chatId, StringUtils.trimToNull(userPrompt),
                    imageData, contentType, ImageGenerationMetadata.parse(metadataText), referenceAttachmentIds);
        } catch (RuntimeException runtimeException) {
            //A failure here must not fail the turn: the model still has something sensible to say,
            //and the alternative is an exception carrying base64 through the tool-calling loop.
            log.error("Could not store a generated image", runtimeException);

            return null;
        }
    }

    /**
     * Deliberately does not describe the image, and says so. The seed and id are here because they
     * are what a user asking "which image was that?" needs the model to be able to repeat.
     */
    private static String modelText(GeneratedImageSummary generatedImageSummary, String metadataText) {
        String seed = generatedImageSummary.seed() == null ? "unknown" : generatedImageSummary.seed().toString();

        return """
                The image was generated successfully and is already displayed to the user.
                Image id: %s
                Seed: %s
                %s

                You cannot see this image. Do not describe what is in it. Confirm briefly that it is \
                ready, and offer to generate another if the user wants changes."""
                .formatted(generatedImageSummary.imageId(), seed, metadataText);
    }

    /**
     * The prompt the model actually sent the tool, which is the only text that describes this
     * image. Absent it the image still stores and renders — the client falls back to generic alt
     * text — so a malformed argument blob is not worth failing over.
     */
    private String prompt(String toolCallInput) {
        try {
            Map<String, Object> arguments = jsonMapper.readValue(toolCallInput, ARGUMENTS_TYPE);

            return StringUtils.trimToNull(String.valueOf(arguments.get(PROMPT)));
        } catch (RuntimeException runtimeException) {
            log.warn("Could not read the prompt out of a generate_image call: {}",
                    runtimeException.getMessage());

            return null;
        }
    }

    /**
     * Identifies an image block structurally, because the {@code type} discriminator is usually not
     * there to identify it with.
     * <p>
     * {@code McpSchema} declares the discriminator with {@code @JsonTypeInfo} on the {@code Content}
     * interface and marks {@code type()} {@code @JsonIgnore}, but Spring AI serialises the content
     * list as a bare {@code Object} — no declared type, so no type serializer, so no {@code "type"}
     * in the JSON. Keying on it silently matched nothing and dropped every generated image.
     * {@code GeneratedImageToolResultShapeTest} pins that behaviour down.
     * <p>
     * The discriminator is still honoured when present: it is what the protocol specifies, and a
     * future Spring AI that emits it should be believed rather than second-guessed.
     */
    private static boolean isImage(Map<String, Object> entry) {
        Object type = entry.get(TYPE);

        if (type != null) {
            return IMAGE.equals(type);
        }

        //Audio blocks also carry data and a mime type, so the mime type is what tells them apart.
        return entry.get(DATA) != null && Strings.CS.startsWith(mimeType(entry), IMAGE_MIME_PREFIX);
    }

    private static boolean isText(Map<String, Object> entry) {
        Object type = entry.get(TYPE);

        if (type != null) {
            return TEXT.equals(type);
        }

        return entry.get(TEXT) != null;
    }

    private static String mimeType(Map<String, Object> entry) {
        Object mimeType = entry.get(MIME_TYPE);

        return mimeType instanceof String mimeTypeText ? mimeTypeText : null;
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
