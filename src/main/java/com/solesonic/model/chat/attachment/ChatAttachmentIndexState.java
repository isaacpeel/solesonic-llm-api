package com.solesonic.model.chat.attachment;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * The index lifecycle of one attachment, without its bytes.
 * <p>
 * What health checks and the healer read: they look at every document of a conversation on every
 * heal run, and loading each one's file data only to inspect a few columns would read whole
 * documents for nothing. The bytes are loaded only for an attachment actually being indexed.
 */
public record ChatAttachmentIndexState(UUID id,
                                       UUID userId,
                                       UUID chatId,
                                       String contentType,
                                       long fileSizeBytes,
                                       Integer chunkCount,
                                       ExtractionFailureReason extractionFailureReason,
                                       boolean indexDetached,
                                       ZonedDateTime indexClaimedAt,
                                       ZonedDateTime indexAttemptedAt) {

    /**
     * A worker holds the lease and it has not gone stale.
     */
    public boolean leased(ZonedDateTime staleBefore) {
        return indexClaimedAt != null && indexClaimedAt.isAfter(staleBefore);
    }
}
