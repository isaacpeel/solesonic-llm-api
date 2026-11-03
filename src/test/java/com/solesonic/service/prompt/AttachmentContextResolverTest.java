package com.solesonic.service.prompt;

import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.etl.ChatDocumentIngestionService;
import com.solesonic.service.prompt.AttachmentContextResolver.AttachmentResolution;
import com.solesonic.service.vision.ImageDescriptionService;
import com.solesonic.service.vision.ImageGenerationRequestClassifier;
import com.solesonic.tools.ImageGenerationTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.service.prompt.AttachmentContextResolver.ImageHandling.DECIDE;
import static com.solesonic.service.prompt.AttachmentContextResolver.ImageHandling.DESCRIBE;
import static com.solesonic.service.prompt.AttachmentContextResolver.ImageHandling.REFERENCE_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentContextResolverTest {

    private static final String EDIT_REQUEST = "create a picture of this person relaxing on a beach";
    private static final String QUESTION = "what is in this photo?";

    @Mock
    private ChatAttachmentService chatAttachmentService;
    @Mock
    private ImageDescriptionService imageDescriptionService;
    @Mock
    private ImageGenerationRequestClassifier imageGenerationRequestClassifier;
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
                chatAttachmentService, imageDescriptionService, imageGenerationRequestClassifier, chatDocumentIngestionService);

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

    private void imageInChat(UUID imageId, String fileName) {
        when(chatAttachmentService.imageSummaries(chatId, Set.of(imageId))).thenReturn(List.of(
                new ChatAttachmentSummary(imageId, UUID.randomUUID(), fileName, null, "image/png", 10L, false, null, false, null)));
    }

    @Test
    void resolve_withNoAttachments_rendersNoContext() {
        partitionInto(Set.of(), Set.of());

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, QUESTION, Set.of(), DESCRIBE);

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

        attachmentContextResolver.resolve(chatId, userId, QUESTION, Set.of(imageId, documentId), DESCRIBE);

        verify(chatAttachmentService).partition(userId, Set.of(imageId, documentId));
        verify(imageDescriptionService).describe(chatId, userId, Set.of(imageId));
        verify(imageDescriptionService, never()).notNeeded(any(), any());
        verify(chatDocumentIngestionService).ingest(chatId, userId, Set.of(documentId));
    }

    /**
     * A tool route sends the images themselves, so describing them would be a vision call nobody
     * reads. The ids still come back for the image tool, every image is still signalled, and
     * documents are indexed as before.
     */
    @Test
    void resolve_referenceOnly_keepsTheImageIdsWithoutDescribingThem() {
        UUID imageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        partitionInto(Set.of(imageId), Set.of(documentId));

        when(chatDocumentIngestionService.ingest(chatId, userId, Set.of(documentId)))
                .thenReturn(List.of("quarterly-report.pdf"));

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, EDIT_REQUEST,
                Set.of(imageId, documentId), REFERENCE_ONLY);

        verify(imageDescriptionService, never()).describe(any(), any(), any());
        verify(imageDescriptionService).notNeeded(chatId, Set.of(imageId));
        verifyNoInteractions(imageGenerationRequestClassifier);

        assertThat(resolution.imageIds()).containsExactly(imageId);
        assertThat(resolution.imageDescriptions()).isEmpty();
        assertThat(resolution.attachmentContext())
                .contains("quarterly-report.pdf")
                .doesNotContain(ImageGenerationTool.GENERATE_IMAGE);
    }

    /**
     * Plain chat: the decision model says the message asks for an image to be generated, so no vision
     * call is made, and the chat model is told the images will reach generate_image by themselves.
     */
    @Test
    void resolve_decide_anImageGenerationRequestSendsTheImagesOnWithoutDescribingThem() {
        UUID imageId = UUID.randomUUID();
        partitionInto(Set.of(imageId), Set.of());
        imageInChat(imageId, "beach.png");

        when(imageGenerationRequestClassifier.requestsImageGeneration(EDIT_REQUEST)).thenReturn(true);

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, EDIT_REQUEST, Set.of(imageId), DECIDE);

        verify(imageDescriptionService, never()).describe(any(), any(), any());
        verify(imageDescriptionService).notNeeded(chatId, Set.of(imageId));

        assertThat(resolution.imageIds()).containsExactly(imageId);
        assertThat(resolution.imageDescriptions()).isEmpty();
        assertThat(resolution.attachmentContext())
                .contains("beach.png")
                .contains(ImageGenerationTool.GENERATE_IMAGE)
                .contains("do not ask the user to upload them again");
    }

    /**
     * Plain chat: anything that is not an image generation request has its images described, exactly
     * as they always were.
     */
    @Test
    void resolve_decide_anyOtherRequestRunsTheVisionModel() {
        UUID imageId = UUID.randomUUID();
        partitionInto(Set.of(imageId), Set.of());
        imageInChat(imageId, "screenshot.png");

        when(imageGenerationRequestClassifier.requestsImageGeneration(QUESTION)).thenReturn(false);
        when(imageDescriptionService.describe(chatId, userId, Set.of(imageId))).thenReturn(List.of(screenshotDescription()));

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, QUESTION, Set.of(imageId), DECIDE);

        verify(imageDescriptionService, never()).notNeeded(any(), any());
        assertThat(resolution.imageDescriptions()).hasSize(1);
        assertThat(resolution.attachmentContext()).contains("a login screen");
    }

    /**
     * No images, nothing to decide: the decision model is never called.
     */
    @Test
    void resolve_decide_withoutImagesAsksNothing() {
        partitionInto(Set.of(), Set.of());

        attachmentContextResolver.resolve(chatId, userId, QUESTION, Set.of(), DECIDE);

        verify(imageGenerationRequestClassifier, never()).requestsImageGeneration(anyString());
        verify(imageDescriptionService, never()).describe(any(), any(), any());
    }

    /**
     * An id that resolves to no image of the chat goes to the describe pass, which reports it as
     * unreadable, so the client still gets its one event.
     */
    @Test
    void resolve_decide_anUnresolvableImageGoesToTheDescribePass() {
        UUID imageId = UUID.randomUUID();
        partitionInto(Set.of(imageId), Set.of());

        when(chatAttachmentService.imageSummaries(chatId, Set.of(imageId))).thenReturn(List.of());

        attachmentContextResolver.resolve(chatId, userId, QUESTION, Set.of(imageId), DECIDE);

        verify(imageGenerationRequestClassifier, never()).requestsImageGeneration(anyString());
        verify(imageDescriptionService).describe(chatId, userId, Set.of(imageId));
    }

    @Test
    void resolve_withImagesOnly_rendersTheDescriptions() {
        UUID imageId = UUID.randomUUID();
        partitionInto(Set.of(imageId), Set.of());

        when(imageDescriptionService.describe(chatId, userId, Set.of(imageId)))
                .thenReturn(List.of(screenshotDescription()));

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, QUESTION, Set.of(imageId), DESCRIBE);

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

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, QUESTION, Set.of(documentId), DESCRIBE);

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

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, QUESTION,
                Set.of(imageId, documentId), DESCRIBE);

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

        AttachmentResolution resolution = attachmentContextResolver.resolve(chatId, userId, QUESTION,
                Set.of(imageId, documentId), DESCRIBE);

        assertThat(resolution.attachmentContext()).isNull();
    }
}
