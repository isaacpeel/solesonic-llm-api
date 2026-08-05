package com.solesonic.model.chat.attachment;

import java.util.UUID;

/**
 * The terminal signal for one image attachment on one turn, emitted as an {@code attachment} SSE
 * event. It closes the {@code progress} frame that opens the vision pass, which on its own never
 * says whether the pass finished.
 * <p>
 * Exactly one of these is emitted per id in {@code ChatRequest.attachmentIds}, before {@code done},
 * whether the image was described or skipped — a client cannot distinguish a missing frame from a
 * failure, so there are no missing frames.
 *
 * @param reason null when {@code described} is true
 */
public record ChatAttachmentEvent(UUID attachmentId,
                                  UUID chatId,
                                  boolean described,
                                  VisionFailureReason reason) {

    public static ChatAttachmentEvent described(UUID chatId, UUID attachmentId) {
        return new ChatAttachmentEvent(attachmentId, chatId, true, null);
    }

    public static ChatAttachmentEvent skipped(UUID chatId, UUID attachmentId, VisionFailureReason reason) {
        return new ChatAttachmentEvent(attachmentId, chatId, false, reason);
    }
}
