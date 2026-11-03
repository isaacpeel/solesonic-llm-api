package com.solesonic.service.vision;

import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.service.chat.events.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.UUID;

import static com.solesonic.model.chat.attachment.VisionFailureReason.NOT_NEEDED;
import static com.solesonic.model.chat.attachment.VisionFailureReason.VISION_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AttachmentVisionSignalsTest {

    @Mock
    private NotificationService notificationService;

    private final UUID chatId = UUID.randomUUID();

    @Test
    void signalsAnIdOnlyOnce() {
        UUID attachmentId = UUID.randomUUID();
        AttachmentVisionSignals signals = new AttachmentVisionSignals(chatId, Set.of(attachmentId), notificationService);

        assertThat(signals.signal(attachmentId, null)).isTrue();
        assertThat(signals.signal(attachmentId, VISION_TIMEOUT)).isFalse();

        verify(notificationService, times(1)).emitAttachment(chatId, ChatAttachmentEvent.described(chatId, attachmentId));
    }

    @Test
    void neverSignalsAnIdThatWasNotTheSends() {
        AttachmentVisionSignals signals = new AttachmentVisionSignals(chatId, Set.of(), notificationService);

        assertThat(signals.signal(UUID.randomUUID(), null)).isFalse();

        verifyNoInteractions(notificationService);
    }

    @Test
    void signalRemainingClosesOnlyWhatIsStillOwed() {
        UUID describedId = UUID.randomUUID();
        UUID remainingId = UUID.randomUUID();
        AttachmentVisionSignals signals = new AttachmentVisionSignals(chatId, Set.of(describedId, remainingId), notificationService);

        signals.signal(describedId, null);
        signals.signalRemaining(NOT_NEEDED);

        verify(notificationService).emitAttachment(chatId, ChatAttachmentEvent.skipped(chatId, remainingId, NOT_NEEDED));
        verify(notificationService, times(2)).emitAttachment(eq(chatId), any());
    }
}
