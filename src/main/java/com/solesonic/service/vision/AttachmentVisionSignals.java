package com.solesonic.service.vision;

import com.solesonic.model.chat.attachment.ChatAttachmentEvent;
import com.solesonic.model.chat.attachment.VisionFailureReason;
import com.solesonic.service.chat.events.NotificationService;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The images of one send that still owe their one {@code attachment} event. Claiming an id is an
 * atomic remove, so whichever path signals it first is the only one that does.
 */
public class AttachmentVisionSignals {

    private final UUID chatId;
    private final Set<UUID> unsignalled = ConcurrentHashMap.newKeySet();
    private final NotificationService notificationService;

    public AttachmentVisionSignals(UUID chatId, Set<UUID> attachmentIds, NotificationService notificationService) {
        this.chatId = chatId;
        this.unsignalled.addAll(attachmentIds);
        this.notificationService = notificationService;
    }

    /**
     * @param failureReason null when the image was described
     * @return whether this call sent the event, false when the id was already signalled or was never
     * this send's
     */
    public boolean signal(UUID attachmentId, VisionFailureReason failureReason) {
        if (!unsignalled.remove(attachmentId)) {
            return false;
        }

        ChatAttachmentEvent chatAttachmentEvent = (failureReason == null)
                ? ChatAttachmentEvent.described(chatId, attachmentId)
                : ChatAttachmentEvent.skipped(chatId, attachmentId, failureReason);

        notificationService.emitAttachment(chatId, chatAttachmentEvent);

        return true;
    }

    public void signalRemaining(VisionFailureReason failureReason) {
        for (UUID attachmentId : List.copyOf(unsignalled)) {
            signal(attachmentId, failureReason);
        }
    }
}
