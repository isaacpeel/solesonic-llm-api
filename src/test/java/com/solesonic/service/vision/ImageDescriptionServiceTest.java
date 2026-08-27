package com.solesonic.service.vision;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.chat.events.NotificationEventMessage;
import com.solesonic.service.chat.events.NotificationService;
import com.solesonic.util.AttachmentContextFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.solesonic.model.chat.attachment.VisionFailureReason.EXCEEDED_IMAGE_LIMIT;
import static com.solesonic.model.chat.attachment.VisionFailureReason.IMAGE_TOO_LARGE;
import static com.solesonic.model.chat.attachment.VisionFailureReason.IMAGE_UNREADABLE;
import static com.solesonic.model.chat.attachment.VisionFailureReason.VISION_TIMEOUT;
import static com.solesonic.model.chat.attachment.VisionFailureReason.VISION_UNAVAILABLE;
import static com.solesonic.service.vision.ImageDescriptionService.MAX_IMAGES_PER_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private OpenAiChatModel visionChatModel;

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

    /**
     * Renders the descriptions into one string the way the A2A route does, so these tests can keep
     * asserting on rendered output. The chat routes instead carry the block as its own message next
     * to the user's — see {@code PromptService} — which is why the service returns descriptions
     * rather than a finished message.
     */
    private String augmented(String message, Set<UUID> attachmentIds) {
        return AttachmentContextFormatter.prepend(
                message, imageDescriptionService.describe(chatId, userId, attachmentIds));
    }

    @Test
    void augmentReturnsTheMessageUnchangedWithoutAttachmentIds() {
        String augmented = augmented("hello", Set.of());

        assertThat(augmented).isEqualTo("hello");
        verifyNoInteractions(chatAttachmentService, visionChatModel);
    }

    @Test
    void augmentReturnsTheMessageUnchangedForNullAttachmentIds() {
        String augmented = augmented("hello", null);

        assertThat(augmented).isEqualTo("hello");
        verifyNoInteractions(chatAttachmentService, visionChatModel);
    }

    @Test
    void augmentDescribesAnImageAndStoresTheDescription() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("a login screen"));

        String augmented = augmented("what is this?", attachmentIds);

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

        String augmented = augmented("what is this?", attachmentIds);

        assertThat(augmented).contains("a login screen");

        verify(visionChatModel, never()).call(any(Prompt.class));
        verify(chatAttachmentService, never()).saveVisionDescription(any(), anyString(), anyString());

        //A reused description is still a described image, and the frontend has no way to know the
        //work was skipped because it had already been done.
        assertThat(describedEvents(1).get(chatAttachment.getId()).described()).isTrue();
    }

    @Test
    void augmentSkipsAnImageAboveTheSizeLimit() {
        ChatAttachment chatAttachment = attachment("huge.png");
        chatAttachment.setFileSizeBytes(DataSize.ofMegabytes(6).toBytes());
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));

        String augmented = augmented("what is this?", attachmentIds);

        assertThat(augmented).isEqualTo("what is this?");
        verify(visionChatModel, never()).call(any(Prompt.class));
    }

    @Test
    void augmentSurvivesAFailingVisionModel() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("the vision server is down"));

        String augmented = augmented("what is this?", attachmentIds);

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

        String augmented = augmented("what are these?", attachmentIds);

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

        String augmented = augmented("what is this?", attachmentIds);

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

        String augmented = augmented("what are these?", Set.of(UUID.randomUUID()));

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

        augmented("what is this?", attachmentIds);

        verify(notificationService).emitProgress(eq(chatId), any(NotificationEventMessage.class));
    }

    /**
     * Captures the terminal events and asserts the contract the frontend relies on: exactly one per
     * requested attachment id, no duplicates.
     */
    private Map<UUID, ChatAttachmentEvent> describedEvents(int expectedCount) {
        ArgumentCaptor<ChatAttachmentEvent> eventCaptor = ArgumentCaptor.forClass(ChatAttachmentEvent.class);

        verify(notificationService, times(expectedCount)).emitAttachment(eq(chatId), eventCaptor.capture());

        Map<UUID, ChatAttachmentEvent> byAttachmentId = eventCaptor.getAllValues().stream()
                .collect(Collectors.toMap(ChatAttachmentEvent::attachmentId, Function.identity()));

        assertThat(byAttachmentId)
                .as("one attachment event per attachment id")
                .hasSize(expectedCount);

        return byAttachmentId;
    }

    @Test
    void augmentEmitsADescribedEventForEachImageItDescribes() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("a login screen"));

        augmented("what is this?", attachmentIds);

        ChatAttachmentEvent chatAttachmentEvent = describedEvents(1).get(chatAttachment.getId());

        assertThat(chatAttachmentEvent.described()).isTrue();
        assertThat(chatAttachmentEvent.reason()).isNull();
        assertThat(chatAttachmentEvent.chatId()).isEqualTo(chatId);
    }

    @Test
    void augmentReportsAReadTimeoutAsVisionTimeout() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class)))
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        String augmented = augmented("what is this?", attachmentIds);

        assertThat(augmented).isEqualTo("what is this?");

        ChatAttachmentEvent chatAttachmentEvent = describedEvents(1).get(chatAttachment.getId());

        assertThat(chatAttachmentEvent.described()).isFalse();
        assertThat(chatAttachmentEvent.reason()).isEqualTo(VISION_TIMEOUT);

        verify(chatAttachmentService).saveVisionFailure(chatAttachment.getId(), VISION_TIMEOUT);
    }

    @Test
    void augmentReportsAnUnreachableVisionHostAsVisionUnavailable() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class)))
                .thenThrow(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        augmented("what is this?", attachmentIds);

        assertThat(describedEvents(1).get(chatAttachment.getId()).reason()).isEqualTo(VISION_UNAVAILABLE);
    }

    @Test
    void augmentReportsAnEmptyDescriptionAsImageUnreadable() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("   "));

        augmented("what is this?", attachmentIds);

        assertThat(describedEvents(1).get(chatAttachment.getId()).reason()).isEqualTo(IMAGE_UNREADABLE);

        verify(chatAttachmentService).saveVisionFailure(chatAttachment.getId(), IMAGE_UNREADABLE);
    }

    @Test
    void augmentReportsAnOversizedImageAsImageTooLarge() {
        ChatAttachment chatAttachment = attachment("huge.png");
        chatAttachment.setFileSizeBytes(DataSize.ofMegabytes(6).toBytes());
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId());

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));

        augmented("what is this?", attachmentIds);

        ChatAttachmentEvent chatAttachmentEvent = describedEvents(1).get(chatAttachment.getId());

        assertThat(chatAttachmentEvent.described()).isFalse();
        assertThat(chatAttachmentEvent.reason()).isEqualTo(IMAGE_TOO_LARGE);

        verify(chatAttachmentService).saveVisionFailure(chatAttachment.getId(), IMAGE_TOO_LARGE);
    }

    @Test
    void augmentReportsEveryImageBeyondTheCapAsExceeded() {
        List<ChatAttachment> attachments = List.of(
                attachment("one.png"),
                attachment("two.png"),
                attachment("three.png"),
                attachment("four.png"),
                attachment("five.png"),
                attachment("six.png"));

        Set<UUID> attachmentIds = attachments.stream()
                .map(ChatAttachment::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        when(chatAttachmentService.attachments(eq(userId), anySet())).thenReturn(attachments);
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("an image"));

        augmented("what are these?", attachmentIds);

        Map<UUID, ChatAttachmentEvent> events = describedEvents(attachments.size());

        for (int index = 0; index < attachments.size(); index++) {
            ChatAttachmentEvent chatAttachmentEvent = events.get(attachments.get(index).getId());

            if (index < MAX_IMAGES_PER_MESSAGE) {
                assertThat(chatAttachmentEvent.described()).isTrue();
            } else {
                assertThat(chatAttachmentEvent.described()).isFalse();
                assertThat(chatAttachmentEvent.reason()).isEqualTo(EXCEEDED_IMAGE_LIMIT);
            }
        }
    }

    /**
     * A missing event is indistinguishable from a failure to the frontend, so an id that resolves to
     * no row still has to produce one.
     */
    @Test
    void augmentEmitsAnEventForAnIdThatResolvesToNoAttachment() {
        ChatAttachment chatAttachment = attachment("screenshot.png");
        UUID unknownAttachmentId = UUID.randomUUID();
        Set<UUID> attachmentIds = Set.of(chatAttachment.getId(), unknownAttachmentId);

        when(chatAttachmentService.attachments(userId, attachmentIds)).thenReturn(List.of(chatAttachment));
        when(visionChatModel.call(any(Prompt.class))).thenReturn(chatResponse("a login screen"));

        augmented("what is this?", attachmentIds);

        Map<UUID, ChatAttachmentEvent> events = describedEvents(2);

        assertThat(events.get(chatAttachment.getId()).described()).isTrue();
        assertThat(events.get(unknownAttachmentId).described()).isFalse();
        assertThat(events.get(unknownAttachmentId).reason()).isEqualTo(IMAGE_UNREADABLE);
    }

    /**
     * The guarantee has to hold even when the failure is not the vision model's: the frontend would
     * otherwise wait for an event that never arrives.
     */
    @Test
    void augmentEmitsAnEventWhenTheAttachmentLookupItselfFails() {
        UUID attachmentId = UUID.randomUUID();
        Set<UUID> attachmentIds = Set.of(attachmentId);

        when(chatAttachmentService.attachments(userId, attachmentIds))
                .thenThrow(new IllegalStateException("the database is down"));

        assertThatThrownBy(() -> augmented("what is this?", attachmentIds))
                .isInstanceOf(IllegalStateException.class);

        assertThat(describedEvents(1).get(attachmentId).reason()).isEqualTo(IMAGE_UNREADABLE);
    }
}
