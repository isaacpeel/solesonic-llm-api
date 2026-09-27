package com.solesonic.service.vision;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.model.chat.attachment.VisionFailureReason;
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
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.solesonic.config.openai.VisionOpenAiConfig.VISION_CHAT_MODEL;
import static com.solesonic.model.chat.attachment.VisionFailureReason.EXCEEDED_IMAGE_LIMIT;
import static com.solesonic.model.chat.attachment.VisionFailureReason.IMAGE_TOO_LARGE;
import static com.solesonic.model.chat.attachment.VisionFailureReason.IMAGE_UNREADABLE;
import static com.solesonic.model.chat.attachment.VisionFailureReason.VISION_TIMEOUT;
import static com.solesonic.model.chat.attachment.VisionFailureReason.VISION_UNAVAILABLE;

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
 * Nothing here can fail a chat turn. Every failure path returns the message unchanged — and emits a
 * {@link ChatAttachmentEvent} saying so, because a turn that answers as though no image was attached
 * is otherwise indistinguishable from one that never had an image.
 */
@Service
public class ImageDescriptionService {
    private static final Logger log = LoggerFactory.getLogger(ImageDescriptionService.class);

    /**
     * Four sequential cold vision calls is already a long wait for a first token; forty is a hung
     * request. A constant rather than a property — this is a guard, not a tuning knob.
     */
    static final int MAX_IMAGES_PER_MESSAGE = 4;

    /**
     * Depth bound on the cause walk in {@link #classify}: a malformed exception chain must not turn
     * a failed describe into a hang.
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    private final OpenAiChatModel visionChatModel;
    private final ChatAttachmentService chatAttachmentService;
    private final NotificationService notificationService;
    private final String describeInstruction;
    private final DataSize maxImageBytes;
    private final String visionModel;

    public ImageDescriptionService(@Qualifier(VISION_CHAT_MODEL) OpenAiChatModel visionChatModel,
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
     * The outcome of one describe attempt: exactly one of the two components is set.
     */
    private record DescribeOutcome(String visionDescription, VisionFailureReason failureReason) {

        static DescribeOutcome described(String visionDescription) {
            return new DescribeOutcome(visionDescription, null);
        }

        static DescribeOutcome skipped(VisionFailureReason failureReason) {
            return new DescribeOutcome(null, failureReason);
        }
    }

    /**
     * Describes every image named by one send, returning the descriptions in send order, or an empty
     * list when there are no attachments, none of them can be described, or the vision model is
     * unreachable.
     * <p>
     * Callers decide how the descriptions reach the model. On the routes that have a message
     * structure they become a message of their own, adjacent to the user's, so the retrieval
     * augmenter — which rewrites only the last user message — cannot absorb them; see
     * {@link AttachmentContextFormatter}.
     * <p>
     * Emits exactly one {@code attachment} event per id in {@code attachmentIds} before returning,
     * whatever happens in between. The frontend has no other way to tell a described image from a
     * silently skipped one, and cannot name a failure it never hears about — so an id that reaches
     * no decision below is reported as undescribed rather than left silent.
     * <p>
     * Blocking, and called from {@code PromptService} on a {@code boundedElastic} thread. It is not
     * {@code @Transactional} on purpose: each repository call is its own short transaction, so no
     * pooled connection is held across a multi-second model call.
     */
    public List<ChatAttachmentDescription> describe(UUID chatId, UUID userId, Set<UUID> attachmentIds) {
        if (CollectionUtils.isEmpty(attachmentIds)) {
            return List.of();
        }

        Set<UUID> unsignalled = new LinkedHashSet<>(attachmentIds);

        try {
            return describeAll(chatId, userId, attachmentIds, unsignalled);
        } finally {
            for (UUID attachmentId : Set.copyOf(unsignalled)) {
                log.warn("No vision outcome was reached for attachment {} on chat {}", attachmentId, chatId);

                signal(chatId, unsignalled, attachmentId, IMAGE_UNREADABLE);
            }
        }
    }

    private List<ChatAttachmentDescription> describeAll(UUID chatId,
                                                        UUID userId,
                                                        Set<UUID> attachmentIds,
                                                        Set<UUID> unsignalled) {
        List<ChatAttachment> attachments = chatAttachmentService.attachments(userId, attachmentIds);

        if (attachments.size() > MAX_IMAGES_PER_MESSAGE) {
            log.warn("Describing the first {} of {} attachments on chat {}",
                    MAX_IMAGES_PER_MESSAGE, attachments.size(), chatId);

            for (ChatAttachment beyondLimit : attachments.subList(MAX_IMAGES_PER_MESSAGE, attachments.size())) {
                chatAttachmentService.saveVisionFailure(beyondLimit.getId(), EXCEEDED_IMAGE_LIMIT);

                signal(chatId, unsignalled, beyondLimit.getId(), EXCEEDED_IMAGE_LIMIT);
            }

            attachments = attachments.subList(0, MAX_IMAGES_PER_MESSAGE);
        }

        List<ChatAttachmentDescription> descriptions = new ArrayList<>(attachments.size());

        for (ChatAttachment attachment : attachments) {
            DescribeOutcome describeOutcome = describeOne(chatId, attachment);

            signal(chatId, unsignalled, attachment.getId(), describeOutcome.failureReason());

            if (describeOutcome.visionDescription() == null) {
                continue;
            }

            descriptions.add(new ChatAttachmentDescription(
                    attachment.getChatMessageId(),
                    attachment.getFileName(),
                    attachment.getDescription(),
                    describeOutcome.visionDescription()));
        }

        return descriptions;
    }

    /**
     * Emits the terminal event for one attachment, at most once per turn.
     *
     * @param failureReason null when the image was described
     */
    private void signal(UUID chatId, Set<UUID> unsignalled, UUID attachmentId, VisionFailureReason failureReason) {
        if (!unsignalled.remove(attachmentId)) {
            return;
        }

        ChatAttachmentEvent chatAttachmentEvent = (failureReason == null)
                ? ChatAttachmentEvent.described(chatId, attachmentId)
                : ChatAttachmentEvent.skipped(chatId, attachmentId, failureReason);

        notificationService.emitAttachment(chatId, chatAttachmentEvent);
    }

    /**
     * One image per call: it keeps each request inside the configured context window and makes each
     * description independently reusable. Calls are sequential because concurrent requests to the
     * same host trigger simultaneous cold loads of the same large model and invite read timeouts.
     */
    private DescribeOutcome describeOne(UUID chatId, ChatAttachment attachment) {
        if (attachment.getVisionDescription() != null) {
            log.debug("Reusing the stored description for attachment {}", attachment.getId());

            return DescribeOutcome.described(attachment.getVisionDescription());
        }

        if (attachment.getFileSizeBytes() > maxImageBytes.toBytes()) {
            log.warn("Attachment {} is {} bytes, above the {} vision limit; leaving it undescribed",
                    attachment.getId(), attachment.getFileSizeBytes(), maxImageBytes);

            chatAttachmentService.saveVisionFailure(attachment.getId(), IMAGE_TOO_LARGE);

            return DescribeOutcome.skipped(IMAGE_TOO_LARGE);
        }

        notificationService.emitProgress(chatId, new NotificationEventMessage(
                attachment.getId().toString(),
                "Reading attached image " + attachment.getFileName(),
                null,
                null));

        long startedAt = System.nanoTime();

        try {
            return callVisionModel(attachment);
        } catch (RuntimeException runtimeException) {
            VisionFailureReason failureReason = classify(runtimeException);

            //Logged with the elapsed time because how long a describe ran is what tells the two
            //common failures apart after the fact: a cold model load that outlived the read timeout
            //looks exactly like an unreachable host until you can see the seconds.
            log.warn("Vision model could not describe attachment {} after {} ({}): {}",
                    attachment.getId(), elapsed(startedAt), failureReason, runtimeException.getMessage());

            chatAttachmentService.saveVisionFailure(attachment.getId(), failureReason);

            return DescribeOutcome.skipped(failureReason);
        }
    }

    private DescribeOutcome callVisionModel(ChatAttachment attachment) {
        Media media = Media.builder()
                .mimeType(MimeType.valueOf(attachment.getContentType()))
                .data(attachment.getFileData())
                .build();

        UserMessage userMessage = UserMessage.builder()
                .text(instruction(attachment))
                .media(List.of(media))
                .build();

        log.info("Describing attachment {} with vision model {}", attachment.getId(), visionModel);

        long startedAt = System.nanoTime();

        ChatResponse chatResponse = visionChatModel.call(new Prompt(userMessage));
        Generation generation = chatResponse.getResult();

        if (generation == null) {
            log.warn("Vision model returned no result for attachment {} after {}",
                    attachment.getId(), elapsed(startedAt));

            chatAttachmentService.saveVisionFailure(attachment.getId(), IMAGE_UNREADABLE);

            return DescribeOutcome.skipped(IMAGE_UNREADABLE);
        }

        String visionDescription = StringUtils.trimToNull(generation.getOutput().getText());

        if (visionDescription == null) {
            log.warn("Vision model returned an empty description for attachment {} after {}",
                    attachment.getId(), elapsed(startedAt));

            chatAttachmentService.saveVisionFailure(attachment.getId(), IMAGE_UNREADABLE);

            return DescribeOutcome.skipped(IMAGE_UNREADABLE);
        }

        log.info("Described attachment {} in {}", attachment.getId(), elapsed(startedAt));

        chatAttachmentService.saveVisionDescription(attachment.getId(), visionDescription, visionModel);

        return DescribeOutcome.described(visionDescription);
    }

    /**
     * Maps a failed vision call onto the closed set of reasons the frontend renders copy for. The
     * distinction worth drawing is timeout versus unreachable: the first is worth retrying once the
     * model is resident, the second is not.
     */
    private static VisionFailureReason classify(RuntimeException runtimeException) {
        Throwable cause = runtimeException;

        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return VISION_TIMEOUT;
            }

            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return VISION_UNAVAILABLE;
            }

            if (cause == cause.getCause()) {
                break;
            }

            cause = cause.getCause();
        }

        return VISION_UNAVAILABLE;
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
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
