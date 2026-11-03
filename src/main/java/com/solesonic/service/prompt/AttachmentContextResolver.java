package com.solesonic.service.prompt;

import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.etl.ChatDocumentIngestionService;
import com.solesonic.service.vision.ImageDescriptionService;
import com.solesonic.service.vision.ImageGenerationRequestClassifier;
import com.solesonic.util.AttachmentContextFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the attachment ids named by one send into what the model is told about them.
 * <p>
 * The two passes are split before either runs, because each guarantees exactly one {@code attachment}
 * SSE event per id it is handed: the sets have to be disjoint and cover every requested id, or a
 * client waits forever for an event that never comes. Images are described by the vision model and
 * reach the model as prose, unless the message asks for an image to be generated, which makes them
 * only inputs for it; documents are indexed and reach it through retrieval, so only their names are
 * rendered here.
 * <p>
 * Every image is signalled here, before the model runs, whichever way it went.
 * <p>
 * Blocking, and called from {@code PromptService} on a {@code boundedElastic} thread. Deliberately
 * not {@code @Transactional}: the calls below run for seconds and must not hold a pooled connection.
 */
@Service
public class AttachmentContextResolver {
    private static final Logger log = LoggerFactory.getLogger(AttachmentContextResolver.class);

    private final ChatAttachmentService chatAttachmentService;
    private final ImageDescriptionService imageDescriptionService;
    private final ImageGenerationRequestClassifier imageGenerationRequestClassifier;
    private final ChatDocumentIngestionService chatDocumentIngestionService;

    public AttachmentContextResolver(ChatAttachmentService chatAttachmentService,
                                     ImageDescriptionService imageDescriptionService,
                                     ImageGenerationRequestClassifier imageGenerationRequestClassifier,
                                     ChatDocumentIngestionService chatDocumentIngestionService) {
        this.chatAttachmentService = chatAttachmentService;
        this.imageDescriptionService = imageDescriptionService;
        this.imageGenerationRequestClassifier = imageGenerationRequestClassifier;
        this.chatDocumentIngestionService = chatDocumentIngestionService;
    }

    /**
     * How the image pass treats one send, chosen by the route.
     */
    public enum ImageHandling {
        DESCRIBE,
        REFERENCE_ONLY,
        DECIDE
    }

    /**
     * @param imageDescriptions the described images in send order, empty when none could be
     *                          described. Kept alongside the rendered block because the A2A routes
     *                          have no message structure to put a separate block into and must
     *                          re-render it inline
     * @param attachmentContext the block the model is shown, or null when the message carried no
     *                          attachment either pass could use
     * @param imageIds          the send's image attachment ids in send order, for an image tool to
     *                          use as reference images. Never shown to the model; may include an id
     *                          that resolves to nothing, which the injector skips
     */
    public record AttachmentResolution(List<ChatAttachmentDescription> imageDescriptions,
                                       String attachmentContext,
                                       Set<UUID> imageIds) {

        public AttachmentResolution {
            imageIds = imageIds == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(imageIds));
        }

        public AttachmentResolution(List<ChatAttachmentDescription> imageDescriptions, String attachmentContext) {
            this(imageDescriptions, attachmentContext, Set.of());
        }
    }

    private record ImagePass(List<ChatAttachmentDescription> imageDescriptions, String imageContext) {
    }

    /**
     * @param message the user's own words, which {@link ImageHandling#DECIDE} classifies
     */
    public AttachmentResolution resolve(UUID chatId,
                                        UUID userId,
                                        String message,
                                        Set<UUID> attachmentIds,
                                        ImageHandling imageHandling) {
        ChatAttachmentService.AttachmentPartition partition = chatAttachmentService
                .partition(userId, attachmentIds);

        ImagePass imagePass = switch (imageHandling) {
            case DESCRIBE -> described(chatId, userId, partition.imageIds());
            case REFERENCE_ONLY -> referencedOnly(chatId, partition.imageIds(), null);
            case DECIDE -> decided(chatId, userId, message, partition.imageIds());
        };

        List<String> indexedDocuments = chatDocumentIngestionService
                .ingest(chatId, userId, partition.documentIds());

        return new AttachmentResolution(imagePass.imageDescriptions(),
                attachmentContext(imagePass.imageContext(), indexedDocuments), partition.imageIds());
    }

    private ImagePass described(UUID chatId, UUID userId, Set<UUID> imageIds) {
        List<ChatAttachmentDescription> imageDescriptions = imageDescriptionService.describe(chatId, userId, imageIds);

        return new ImagePass(imageDescriptions, AttachmentContextFormatter.context(imageDescriptions));
    }

    private ImagePass referencedOnly(UUID chatId, Set<UUID> imageIds, String imageContext) {
        imageDescriptionService.notNeeded(chatId, imageIds);

        return new ImagePass(List.of(), imageContext);
    }

    /**
     * The decision model reads the user's words and says whether they ask for an image to be
     * generated. If so, the images are only its inputs and are never described. If not, they are
     * described, even though the chat model may still go on to generate an image. An id that resolves
     * to no image of the chat goes to the describe pass, which reports it as unreadable.
     */
    private ImagePass decided(UUID chatId, UUID userId, String message, Set<UUID> imageIds) {
        if (imageIds.isEmpty()) {
            return new ImagePass(List.of(), null);
        }

        List<ChatAttachmentSummary> images = chatAttachmentService.imageSummaries(chatId, imageIds);

        if (images.isEmpty()) {
            return described(chatId, userId, imageIds);
        }

        boolean requestsImageGeneration = imageGenerationRequestClassifier.requestsImageGeneration(message);

        log.info("Chat {} {} {} attached image(s)", chatId,
                requestsImageGeneration ? "asks for an image to be generated; not describing" : "is not an image generation request; describing",
                images.size());

        if (!requestsImageGeneration) {
            return described(chatId, userId, imageIds);
        }

        List<String> fileNames = images.stream()
                .map(ChatAttachmentSummary::fileName)
                .toList();

        return referencedOnly(chatId, imageIds, AttachmentContextFormatter.referenceContext(fileNames));
    }

    /**
     * Joins what the model is told about this message's attachments into one block, documents
     * first: the document note only says which files exist, while the image block is about the
     * images themselves, and that reads better closest to the question.
     *
     * @return the block, or null when the message carried no attachment either pass could use
     */
    private static String attachmentContext(String imageContext, List<String> indexedDocuments) {
        String documentContext = AttachmentContextFormatter.documentContext(indexedDocuments);

        if (documentContext == null) {
            return imageContext;
        }

        if (imageContext == null) {
            return documentContext;
        }

        return documentContext + System.lineSeparator() + imageContext;
    }
}
