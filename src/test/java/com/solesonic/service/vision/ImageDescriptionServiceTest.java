package com.solesonic.service.vision;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.chat.events.NotificationEventMessage;
import com.solesonic.service.chat.events.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.solesonic.service.vision.ImageDescriptionService.MAX_IMAGES_PER_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageDescriptionServiceTest {

    private static final String VISION_MODEL = "qwen2.5vl";

    @Mock
    private OllamaChatModel visionChatModel;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private NotificationService notificationService;

    private ImageDescriptionService imageDescriptionService;

    private final UUID chatId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ByteArrayResource describeInstruction =
                new ByteArrayResource("Describe this image.".getBytes(StandardCharsets.UTF_8));

        imageDescriptionService = new ImageDescriptionService(
                visionChatModel,
                chatAttachmentService,
                notificationService,
                describeInstruction,
                DataSize.ofMegabytes(5),
                VISION_MODEL);
    }

    private ChatAttachment attachment(String fileName) {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(UUID.randomUUID());
        chatAttachment.setUserId(userId);
        chatAttachment.setChatId(chatId);
        chatAttachment.setChatMessageId(UUID.randomUUID());
        chatAttachment.setFileName(fileName);
        chatAttachment.setContentType("image/png");
        chatAttachment.setFileData("image-bytes".getBytes(StandardCharsets.UTF_8));
        chatAttachment.setFileSizeBytes(11L);

        return chatAttachment;
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void augmentReturnsTheMessageUnchangedWithoutAttachmentIds() {
        String augmented = imageDescriptionService.augment(chatId, userId, "hello", Set.of());

        assertThat(augmented).isEqualTo("hello");
        verifyNoInteractions(chatAttachmentService, visionChatModel);
    }

    @Test
    void augmentReturnsTheMessageUnchangedForNullAttachmentIds() {
        String augmented = imageDescriptionService.augment(chatId, userId, "hello", null);

        assertThat(augmented).isEqualTo("hello");
        verifyNoInteractions(chatAttachmentService, visionChatModel);
    }

    @Test
    void augmentDescribesAnImageAndStoresTheDescription() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("a login screen"));

        String augmented = imageDescriptionService.augment(chatId, userId, "what is this?", attachmentIds);

        assertThat(augmented)
                .contains("Image 1 — screenshot.png:")
                .contains("a login screen")
                .endsWith("what is this?");

        verify(chatAttachmentService)
                .saveVisionDescription(chatAttachment.getId(), "a login screen", VISION_MODEL);
    }

    @Test
    void augmentReusesAStoredDescriptionWithoutCallingTheModel() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        chatAttachment.setVisionDescription("a login screen");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));

        String augmented = imageDescriptionService.augment(chatId, userId, "what is this?", attachmentIds);

        assertThat(augmented).contains("a login screen");

        verify(visionChatModel, never()).call(any(Prompt.class));
        verify(chatAttachmentService, never()).saveVisionDescription(any(), anyString(), anyString());
        verifyNoInteractions(notificationService);
    }

    @Test
    void augmentSkipsAnImageAboveTheSizeLimit() {
        ChatAttachment chatAttachment = attachment("huge.png");
        chatAttachment.setFileSizeBytes(DataSize.ofMegabytes(6).toBytes());
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));

        String augmented = imageDescriptionService.augment(chatId, userId, "what is this?", attachmentIds);

        assertThat(augmented).isEqualTo("what is this?");
        verify(visionChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void augmentSurvivesAFailingVisionModel() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("ollama is down"));

        String augmented = imageDescriptionService.augment(chatId, userId, "what is this?", attachmentIds);

        assertThat(augmented).isEqualTo("what is this?");
        verify(chatAttachmentService, never()).saveVisionDescription(any(), anyString(), anyString());
    }

    @Test
    void augmentStillDescribesTheHealthyImageWhenAnotherFails() {
        ChatAttachment failing = attachment("broken.png");
        ChatAttachment healthy = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(failing.getId(), healthy.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(failing, healthy));
        when(visionChatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("unreadable image"))
                .thenReturn(chatResponse("a login screen"));

        String augmented = imageDescriptionService.augment(chatId, userId, "what are these?", attachmentIds);

        assertThat(augmented)
                .contains("Image 1 — screenshot.png:")
                .doesNotContain("broken.png");

        verify(chatAttachmentService)
                .saveVisionDescription(healthy.getId(), "a login screen", VISION_MODEL);
    }

    @Test
    void augmentDoesNotStoreAnEmptyDescription() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("   "));

        String augmented = imageDescriptionService.augment(chatId, userId, "what is this?", attachmentIds);

        assertThat(augmented).isEqualTo("what is this?");
        verify(chatAttachmentService, never()).saveVisionDescription(any(), anyString(), anyString());
    }

    @Test
    void augmentDescribesNoMoreThanTheImageCap() {
        List<ChatAttachment> attachments = List.of(
                attachment("one.png"),
                attachment("two.png"),
                attachment("three.png"),
                attachment("four.png"),
                attachment("five.png"),
                attachment("six.png"));

        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(attachments);
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("an image"));

        String augmented = imageDescriptionService.augment(
                chatId, userId, "what are these?", Set.of(UUID.randomUUID()));

        verify(visionChatModel, times(MAX_IMAGES_PER_MESSAGE)).call(any(Prompt.class));

        assertThat(augmented)
                .contains("Image %d".formatted(MAX_IMAGES_PER_MESSAGE))
                .doesNotContain("Image %d".formatted(MAX_IMAGES_PER_MESSAGE + 1))
                .doesNotContain("five.png");
    }

    @Test
    void augmentEmitsProgressForEachImageItDescribes() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("a login screen"));

        imageDescriptionService.augment(chatId, userId, "what is this?", attachmentIds);

        verify(notificationService).emitProgress(eq(chatId), any(NotificationEventMessage.class));
    }
}
