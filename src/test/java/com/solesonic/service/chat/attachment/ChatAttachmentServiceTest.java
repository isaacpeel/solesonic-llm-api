package com.solesonic.service.chat.attachment;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.repository.chat.ChatAttachmentRepository;
import com.solesonic.scope.UserRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceTest {

    @Mock
    private ChatAttachmentRepository chatAttachmentRepository;

    @Mock
    private UserRequestContext userRequestContext;

    private ChatAttachmentService chatAttachmentService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chatAttachmentService = new ChatAttachmentService(
                chatAttachmentRepository, userRequestContext, Duration.ofHours(24));

        lenient().when(userRequestContext.getUserId()).thenReturn(userId);
        lenient().when(chatAttachmentRepository.save(any(ChatAttachment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MockMultipartFile file(String contentType) {
        return new MockMultipartFile(
                "file", "screenshot.png", contentType, "not-really-an-image".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void stageAcceptsSupportedImageType() {
        ChatAttachmentSummary summary = chatAttachmentService.stage(file("image/png"), "a login screen");

        assertThat(summary.contentType()).isEqualTo("image/png");
        assertThat(summary.fileName()).isEqualTo("screenshot.png");
        assertThat(summary.description()).isEqualTo("a login screen");
    }

    @Test
    void stageRejectsUnsupportedContentType() {
        MockMultipartFile pdf = file("application/pdf");

        assertThatThrownBy(() -> chatAttachmentService.stage(pdf, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void stageRejectsMissingContentType() {
        MockMultipartFile noContentType = file(null);

        assertThatThrownBy(() -> chatAttachmentService.stage(noContentType, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void stageNormalizesBlankDescriptionToNull() {
        ChatAttachmentSummary summary = chatAttachmentService.stage(file("image/jpeg"), "   ");

        assertThat(summary.description()).isNull();
    }

    @Test
    void bindSucceedsWhenEveryIdIsClaimed() {
        Set<UUID> attachmentIds = Set.of(UUID.randomUUID(), UUID.randomUUID());
        UUID chatId = UUID.randomUUID();
        UUID chatMessageId = UUID.randomUUID();

        when(chatAttachmentRepository.bind(anySet(), any(), any(), any())).thenReturn(2);

        chatAttachmentService.bind(userId, chatId, chatMessageId, attachmentIds);
    }

    @Test
    void bindFailsWhenAnIdWasAlreadyClaimed() {
        Set<UUID> attachmentIds = Set.of(UUID.randomUUID(), UUID.randomUUID());
        UUID chatId = UUID.randomUUID();
        UUID chatMessageId = UUID.randomUUID();

        when(chatAttachmentRepository.bind(anySet(), any(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> chatAttachmentService.bind(userId, chatId, chatMessageId, attachmentIds))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void bindIsANoOpWithoutAttachmentIds() {
        chatAttachmentService.bind(userId, UUID.randomUUID(), UUID.randomUUID(), null);
    }
}
