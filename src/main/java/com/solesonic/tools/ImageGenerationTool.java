package com.solesonic.tools;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.service.image.ImageGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.solesonic.mcp.client.IdentityToolCallback.USER_ID;
import static com.solesonic.service.prompt.PromptService.CHAT_ID;
import static com.solesonic.service.prompt.PromptService.REFERENCE_ATTACHMENT_IDS;

/**
 * {@code generate_image}: the chat model deciding to make an image, or the user asking with
 * {@code /generate_image}.
 * <p>
 * The image never passes through the tool result. It is stored and announced on the chat's side
 * channel here, and the model is told only that it exists — and that it cannot see it, because a
 * model told only that an image was generated will describe one anyway. The images the user attached
 * to the message are sent as references from the tool context; the model never handles their bytes.
 */
@Component
public class ImageGenerationTool implements LocalTool {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationTool.class);

    public static final String GENERATE_IMAGE = "generate_image";

    private static final String NO_IMAGE = "The image could not be generated. Tell the user it failed and offer to try again.";

    private final ImageGenerationService imageGenerationService;
    private final NotificationService notificationService;

    public ImageGenerationTool(ImageGenerationService imageGenerationService, NotificationService notificationService) {
        this.imageGenerationService = imageGenerationService;
        this.notificationService = notificationService;
    }

    @Tool(name = GENERATE_IMAGE, description = "Generates an image from a text prompt and shows it to the user. "
            + "Images the user attached to this message are sent automatically as reference images; never describe "
            + "them in the prompt.")
    public String generateImage(@ToolParam(description = "What the image should show, in the user's own words.") String prompt,
                                ToolContext toolContext) {
        Map<String, Object> context = toolContext.getContext();

        if (!(context.get(USER_ID) instanceof UUID userId)) {
            log.error("generate_image was called with no user in the tool context; nothing generated");

            return NO_IMAGE;
        }

        UUID chatId = context.get(CHAT_ID) instanceof UUID chatIdValue ? chatIdValue : null;

        try {
            GeneratedImageSummary generatedImageSummary =
                    imageGenerationService.generateInChat(prompt, referenceAttachmentIds(context), userId, chatId);

            if (chatId != null) {
                notificationService.emitGeneratedImage(chatId, generatedImageSummary);
            }

            return """
                    The image was generated successfully and is already displayed to the user.
                    Image id: %s

                    You cannot see this image. Do not describe what is in it. Confirm briefly that it is \
                    ready, and offer to generate another if the user wants changes."""
                    .formatted(generatedImageSummary.imageId());
        } catch (ImageGenerationException imageGenerationException) {
            log.warn("generate_image failed for chat {}: {}", chatId, imageGenerationException.getErrorCode());

            return """
                    The image could not be generated: %s
                    Explain this to the user in plain words."""
                    .formatted(imageGenerationException.getMessage());
        } catch (RuntimeException runtimeException) {
            log.error("generate_image failed for chat {}: {}", chatId, runtimeException.getClass().getName());
            log.debug("generate_image failure detail", runtimeException);

            return NO_IMAGE;
        }
    }

    private static List<UUID> referenceAttachmentIds(Map<String, Object> context) {
        if (!(context.get(REFERENCE_ATTACHMENT_IDS) instanceof Collection<?> attachmentIds)) {
            return List.of();
        }

        return attachmentIds.stream()
                .filter(UUID.class::isInstance)
                .map(UUID.class::cast)
                .toList();
    }
}
