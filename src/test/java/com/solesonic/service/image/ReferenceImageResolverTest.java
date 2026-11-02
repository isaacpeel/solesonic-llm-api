package com.solesonic.service.image;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceImageResolverTest {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    @Mock
    private ChatAttachmentService chatAttachmentService;

    private ReferenceImageResolver referenceImageResolver;
    private UUID userId;

    @BeforeEach
    void setUp() {
        referenceImageResolver = new ReferenceImageResolver(chatAttachmentService);
        userId = UUID.randomUUID();
    }

    /**
     * The image service detects PNG, JPEG and WebP from the bytes and nothing else; a document is never
     * an image whatever id it arrived under. Ownership is the repository's {@code where} clause, so
     * another user's id simply never comes back.
     */
    @Test
    void usableAttachments_keepsOnlyThePngJpegAndWebpImagesTheUserOwns() {
        UUID png = UUID.randomUUID();
        UUID jpeg = UUID.randomUUID();
        UUID webp = UUID.randomUUID();
        UUID gif = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        UUID someoneElses = UUID.randomUUID();

        when(chatAttachmentService.attachments(any(), anySet())).thenReturn(List.of(
                attachment(png, "image/png", PNG_BYTES),
                attachment(jpeg, "IMAGE/JPEG", JPEG_BYTES),
                attachment(webp, "image/webp", PNG_BYTES),
                attachment(gif, "image/gif", new byte[]{1}),
                attachment(document, "application/pdf", new byte[]{2}),
                attachment(empty, "image/png", new byte[0])));

        assertThat(referenceImageResolver.usableAttachments(userId, List.of(png, jpeg, webp, gif, document, empty, someoneElses)))
                .extracting(ChatAttachment::getId)
                .containsExactly(png, jpeg, webp);
    }

    /**
     * The service routes on order, so the send's order decides, not when each attachment was staged.
     */
    @Test
    void usableAttachments_followsTheRequestedOrderNotCreationOrder() {
        UUID stagedFirst = UUID.randomUUID();
        UUID stagedSecond = UUID.randomUUID();
        when(chatAttachmentService.attachments(any(), anySet())).thenReturn(List.of(
                attachment(stagedFirst, "image/png", PNG_BYTES),
                attachment(stagedSecond, "image/png", PNG_BYTES)));

        assertThat(referenceImageResolver.usableAttachments(userId, List.of(stagedSecond, stagedFirst)))
                .extracting(ChatAttachment::getId)
                .containsExactly(stagedSecond, stagedFirst);
    }

    @Test
    void usableAttachments_withoutAUserOrIdsLooksNothingUp() {
        assertThat(referenceImageResolver.usableAttachments(null, List.of(UUID.randomUUID()))).isEmpty();
        assertThat(referenceImageResolver.usableAttachments(userId, List.of())).isEmpty();

        verifyNoInteractions(chatAttachmentService);
    }

    private ChatAttachment attachment(UUID attachmentId, String contentType, byte[] fileData) {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(attachmentId);
        chatAttachment.setUserId(userId);
        chatAttachment.setContentType(contentType);
        chatAttachment.setFileData(fileData);

        return chatAttachment;
    }
}
