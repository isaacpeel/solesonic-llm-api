package com.solesonic.service.prompt;

import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.etl.ChatDocumentIngestionService;
import com.solesonic.service.vision.ImageDescriptionService;
import com.solesonic.util.AttachmentContextFormatter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the attachment ids named by one send into what the model is told about them.
 * <p>
 * The two passes are split before either runs, because each guarantees exactly one {@code attachment}
 * SSE event per id it is handed: the sets have to be disjoint and cover every requested id, or a
 * client waits forever for an event that never comes. Images are described by the vision model and
 * reach the model as prose; documents are indexed and reach it through retrieval, so only their
 * names are rendered here.
 * <p>
 * Blocking, and called from {@code PromptService} on a {@code boundedElastic} thread. Deliberately
 * not {@code @Transactional}: the calls below run for seconds and must not hold a pooled connection.
 */
@Service
public class AttachmentContextResolver {

    private final ChatAttachmentService chatAttachmentService;
    private final ImageDescriptionService imageDescriptionService;
    private final ChatDocumentIngestionService chatDocumentIngestionService;

    public AttachmentContextResolver(ChatAttachmentService chatAttachmentService,
                                     ImageDescriptionService imageDescriptionService,
                                     ChatDocumentIngestionService chatDocumentIngestionService) {
        this.chatAttachmentService = chatAttachmentService;
        this.imageDescriptionService = imageDescriptionService;
        this.chatDocumentIngestionService = chatDocumentIngestionService;
    }

    /**
     * @param imageDescriptions the described images in send order, empty when none could be
     *                          described. Kept alongside the rendered block because the A2A routes
     *                          have no message structure to put a separate block into and must
     *                          re-render it inline
     * @param attachmentContext the block the model is shown, or null when the message carried no
     *                          attachment either pass could use
     */
    public record AttachmentResolution(List<ChatAttachmentDescription> imageDescriptions,
                                       String attachmentContext) {
    }

    public AttachmentResolution resolve(UUID chatId, UUID userId, Set<UUID> attachmentIds) {
        ChatAttachmentService.AttachmentPartition partition = chatAttachmentService
                .partition(userId, attachmentIds);

        List<ChatAttachmentDescription> imageDescriptions = imageDescriptionService
                .describe(chatId, userId, partition.imageIds());

        List<String> indexedDocuments = chatDocumentIngestionService
                .ingest(chatId, userId, partition.documentIds());

        return new AttachmentResolution(imageDescriptions,
                attachmentContext(imageDescriptions, indexedDocuments));
    }

    /**
     * Joins what the model is told about this message's attachments into one block, documents
     * first: the document note only says which files exist, while the image block carries actual
     * content, and the content reads better closest to the question.
     *
     * @return the block, or null when the message carried no attachment either pass could use
     */
    private static String attachmentContext(List<ChatAttachmentDescription> imageDescriptions,
                                            List<String> indexedDocuments) {
        String imageContext = AttachmentContextFormatter.context(imageDescriptions);
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
