package com.solesonic.service.vision;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.chat.events.NotificationEventMessage;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.util.AttachmentContextFormatter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.config.olllama.VisionOllamaConfig.VISION_CHAT_MODEL;

/**
 * Describes image attachments with a vision model, and folds those descriptions into the text of the
 * user message so that the conversational model — which never sees the image bytes — can answer
 * about them.
 * <p>
 * A description is generated once per attachment and stored on its row, so the cost is paid on the
 * turn the image is sent and never again. Descriptions are deliberately independent of the user's
 * question: the vision model is given a fixed instruction plus the user's own note, which is what
 * makes a stored description safe to reuse for every later question about that image.
 * <p>
 * Nothing here can fail a chat turn. Every failure path returns the message unchanged.
 */
@Service
public class ImageDescriptionService {
    private static final Logger log = LoggerFactory.getLogger(ImageDescriptionService.class);

    /**
     * Four sequential cold vision calls is already a long wait for a first token; forty is a hung
     * request. A constant rather than a property — this is a guard, not a tuning knob.
     */
    static final int MAX_IMAGES_PER_MESSAGE = 4;

    private final OllamaChatModel visionChatModel;
    private final ChatAttachmentService chatAttachmentService;
    private final NotificationService notificationService;
    private final String describeInstruction;
    private final DataSize maxImageBytes;
    private final String visionModel;

    public ImageDescriptionService(@Qualifier(VISION_CHAT_MODEL) OllamaChatModel visionChatModel,
                                   ChatAttachmentService chatAttachmentService,
                                   NotificationService notificationService,
                                   @Value("classpath:prompts/image-description-prompt.st") Resource describeInstruction,
                                   @Value("${solesonic.llm.vision.max-image-bytes}") DataSize maxImageBytes,
                                   @Value("${solesonic.llm.vision.model}") String visionModel) {
        this.visionChatModel = visionChatModel;
        this.chatAttachmentService = chatAttachmentService;
        this.notificationService = notificationService;
        this.describeInstruction = readInstruction(describeInstruction);
        this.maxImageBytes = maxImageBytes;
        this.visionModel = visionModel;
    }

    private static String readInstruction(Resource describeInstruction) {
        try {
            return describeInstruction.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ioException) {
            throw new IllegalStateException("Could not read the image description prompt", ioException);
        }
    }

    /**
     * Returns {@code message} with a described-images block prepended, or unchanged when there are no
     * attachments, none of them can be described, or the vision model is unreachable.
     * <p>
     * Blocking, and called from {@code PromptService} on a {@code boundedElastic} thread. It is not
     * {@code @Transactional} on purpose: each repository call is its own short transaction, so no
     * pooled connection is held across a multi-second model call.
     */
    public String augment(UUID chatId, UUID userId, String message, Set<UUID> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return message;
        }

        List<ChatAttachment> attachments = chatAttachmentService.attachments(userId, attachmentIds);

        if (attachments.size() > MAX_IMAGES_PER_MESSAGE) {
            log.warn("Describing the first {} of {} attachments on chat {}",
                    MAX_IMAGES_PER_MESSAGE, attachments.size(), chatId);

            attachments = attachments.subList(0, MAX_IMAGES_PER_MESSAGE);
        }

        List<ChatAttachmentDescription> descriptions = new ArrayList<>(attachments.size());

        for (ChatAttachment attachment : attachments) {
            String visionDescription = describe(chatId, attachment);

            if (visionDescription == null) {
                continue;
            }

            descriptions.add(new ChatAttachmentDescription(
                    attachment.getChatMessageId(),
                    attachment.getFileName(),
                    attachment.getDescription(),
                    visionDescription));
        }

        return AttachmentContextFormatter.prepend(message, descriptions);
    }

    /**
     * One image per call: it keeps each request inside the configured context window and makes each
     * description independently reusable. Calls are sequential because concurrent requests to the
     * same host trigger simultaneous cold loads of the same large model and invite read timeouts.
     *
     * @return the description, or {@code null} when this image could not be described
     */
    private String describe(UUID chatId, ChatAttachment attachment) {
        if (attachment.getVisionDescription() != null) {
            log.debug("Reusing the stored description for attachment {}", attachment.getId());

            return attachment.getVisionDescription();
        }

        if (attachment.getFileSizeBytes() > maxImageBytes.toBytes()) {
            log.warn("Attachment {} is {} bytes, above the {} vision limit; leaving it undescribed",
                    attachment.getId(), attachment.getFileSizeBytes(), maxImageBytes);

            return null;
        }

        notificationService.emitProgress(chatId, new NotificationEventMessage(
                attachment.getId().toString(),
                "Reading attached image " + attachment.getFileName(),
                null,
                null));

        try {
            return callVisionModel(attachment);
        } catch (RuntimeException exception) {
            log.warn("Vision model could not describe attachment {}: {}",
                    attachment.getId(), exception.getMessage());

            return null;
        }
    }

    private String callVisionModel(ChatAttachment attachment) {
        Media media = Media.builder()
                .mimeType(MimeType.valueOf(attachment.getContentType()))
                .data(attachment.getFileData())
                .build();

        UserMessage userMessage = UserMessage.builder()
                .text(instruction(attachment))
                .media(List.of(media))
                .build();

        log.info("Describing attachment {} with vision model {}", attachment.getId(), visionModel);

        ChatResponse chatResponse = visionChatModel.call(new Prompt(userMessage));
        Generation generation = chatResponse.getResult();

        if (generation == null) {
            log.warn("Vision model returned no result for attachment {}", attachment.getId());

            return null;
        }

        String visionDescription = StringUtils.trimToNull(generation.getOutput().getText());

        if (visionDescription == null) {
            log.warn("Vision model returned an empty description for attachment {}", attachment.getId());

            return null;
        }

        chatAttachmentService.saveVisionDescription(attachment.getId(), visionDescription, visionModel);

        return visionDescription;
    }

    /**
     * The client-supplied note is the cheapest quality win available here: "the error I get on save"
     * turns a generic screenshot description into a useful one.
     */
    private String instruction(ChatAttachment attachment) {
        if (StringUtils.isBlank(attachment.getDescription())) {
            return describeInstruction;
        }

        return describeInstruction
                + System.lineSeparator()
                + "The user's note about this image: \"%s\"".formatted(attachment.getDescription());
    }
}
