package com.solesonic.model.chat.attachment;

import java.util.UUID;

/**
 * The terminal signal for one attachment on one turn, emitted as an {@code attachment} SSE event.
 * It closes the {@code progress} frame that opens the vision or extraction pass, which on its own
 * never says whether the pass finished.
 * <p>
 * Exactly one of these is emitted per id in {@code ChatRequest.attachmentIds}, before {@code done},
 * whether the attachment was handled or skipped — a client cannot distinguish a missing frame from
 * a failure, so there are no missing frames.
 * <p>
 * One record covers both kinds because a client renders one attachment chip either way. An image
 * moves {@code described}; a document moves {@code indexed} and {@code chunkCount}. The pair the
 * attachment is not is left at its empty value rather than split across two event names.
 *
 * @param reason           null when {@code described} is true, and on every document
 * @param indexed          whether a document attachment's text reached the vector store
 * @param extractionReason null when {@code indexed} is true, and on every image
 * @param chunkCount       how many chunks a document was indexed as, null when it was not
 */
public record ChatAttachmentEvent(UUID attachmentId,
                                  UUID chatId,
                                  boolean described,
                                  VisionFailureReason reason,
                                  boolean indexed,
                                  ExtractionFailureReason extractionReason,
                                  Integer chunkCount) {

    public static ChatAttachmentEvent described(UUID chatId, UUID attachmentId) {
        return new ChatAttachmentEvent(attachmentId, chatId, true, null, false, null, null);
    }

    public static ChatAttachmentEvent skipped(UUID chatId, UUID attachmentId, VisionFailureReason reason) {
        return new ChatAttachmentEvent(attachmentId, chatId, false, reason, false, null, null);
    }

    public static ChatAttachmentEvent indexed(UUID chatId, UUID attachmentId, int chunkCount) {
        return new ChatAttachmentEvent(attachmentId, chatId, false, null, true, null, chunkCount);
    }

    public static ChatAttachmentEvent notIndexed(UUID chatId, UUID attachmentId, ExtractionFailureReason reason) {
        return new ChatAttachmentEvent(attachmentId, chatId, false, null, false, reason, null);
    }
}
