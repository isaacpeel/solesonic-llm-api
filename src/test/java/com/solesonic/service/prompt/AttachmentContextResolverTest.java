package com.solesonic.service.prompt;

import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.etl.ChatDocumentIngestionService;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.vision.ImageDescriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentContextResolverTest {

    @Mock
    private ChatAttachmentService chatAttachmentService;
    @Mock
    private ImageDescriptionService imageDescriptionService;
    @Mock
    private ChatDocumentIngestionService chatDocumentIngestionService;

    private UUID chatId;
    private UUID userId;

    private AttachmentContextResolver attachmentContextResolver;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();
        userId = UUID.randomUUID();

        attachmentContextResolver = new AttachmentContextResolver(
                chatAttachmentService, imageDescriptionService, chatDocumentIngestionService);

        lenient().when(imageDescriptionService.describe(any(), any(), any())).thenReturn(List.of());
        lenient().when(chatDocumentIngestionService.ingest(any(), any(), any())).thenReturn(List.of());
    }

    private void partitionInto(Set<UUID> imageIds, Set<UUID> documentIds) {
        when(chatAttachmentService.partition(any(), any()))
                .thenReturn(new ChatAttachmentService.AttachmentPartition(imageIds, documentIds));
    }

    private static ChatAttachmentDescription screenshotDescription() {
        return new ChatAttachmentDescription(UUID.randomUUID(), "screenshot.png", null, "a login screen");
    }

    @Test
    void resolve_withNoAttachments_rendersNoContext() {
        partitionInto(Set.of(), Set.of());

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, Set.of());

        assertThat(resolution.imageDescriptions()).isEmpty();
        assertThat(resolution.attachmentContext()).isNull();
    }

    /**
     * Each pass emits exactly one {@code attachment} SSE event per id it is handed, so the split has
     * to reach the two passes intact — an id sent to both is signalled twice, and one sent to
     * neither leaves a client waiting for an event that never comes.
     */
    @Test
    void resolve_handsEachPassOnlyItsOwnHalfOfTheSplit() {
        UUID imageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        partitionInto(Set.of(imageId), Set.of(documentId));

        attachmentContextResolver.resolve(chatId, userId, Set.of(imageId, documentId));

        verify(chatAttachmentService).partition(userId, Set.of(imageId, documentId));
        verify(imageDescriptionService).describe(chatId, userId, Set.of(imageId));
        verify(chatDocumentIngestionService).ingest(chatId, userId, Set.of(documentId));
    }

    @Test
    void resolve_withImagesOnly_rendersTheDescriptions() {
        UUID imageId = UUID.randomUUID();
        partitionInto(Set.of(imageId), Set.of());

        when(imageDescriptionService.describe(chatId, userId, Set.of(imageId)))
                .thenReturn(List.of(screenshotDescription()));

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, Set.of(imageId));

        assertThat(resolution.imageDescriptions()).hasSize(1);
        assertThat(resolution.attachmentContext())
                .contains("screenshot.png")
                .contains("a login screen");
    }

    /**
     * A document's text reaches the model through retrieval, so all the block carries is that the
     * file exists — which is what a question like "summarise the attachment" depends on.
     */
    @Test
    void resolve_withDocumentsOnly_namesTheIndexedFiles() {
        UUID documentId = UUID.randomUUID();
        partitionInto(Set.of(), Set.of(documentId));

        when(chatDocumentIngestionService.ingest(chatId, userId, Set.of(documentId)))
                .thenReturn(List.of("quarterly-report.pdf"));

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, Set.of(documentId));

        assertThat(resolution.imageDescriptions()).isEmpty();
        assertThat(resolution.attachmentContext()).contains("quarterly-report.pdf");
    }

    /**
     * Documents first: their note only says which files exist, while the image block carries actual
     * content, and content reads better closest to the question.
     */
    @Test
    void resolve_withBoth_putsTheDocumentBlockBeforeTheImageBlock() {
        UUID imageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        partitionInto(Set.of(imageId), Set.of(documentId));

        when(imageDescriptionService.describe(chatId, userId, Set.of(imageId)))
                .thenReturn(List.of(screenshotDescription()));
        when(chatDocumentIngestionService.ingest(chatId, userId, Set.of(documentId)))
                .thenReturn(List.of("quarterly-report.pdf"));

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId,
                Set.of(imageId, documentId));

        assertThat(resolution.attachmentContext())
                .containsSubsequence("quarterly-report.pdf", "screenshot.png");
    }

    /**
     * An attachment that neither pass could use must render nothing at all, rather than an empty
     * block claiming files were attached.
     */
    @Test
    void resolve_whenNeitherPassProducedAnything_rendersNoContext() {
        UUID imageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        partitionInto(Set.of(imageId), Set.of(documentId));

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId,
                Set.of(imageId, documentId));

        assertThat(resolution.attachmentContext()).isNull();
    }
}
