package com.solesonic.model.chat.attachment;

import java.util.UUID;

/**
 * @param description              the note the client supplied at upload, never model output
 * @param described                whether the vision model produced a description for this image.
 *                                 The durable half of the {@code attachment} SSE frame: it is what
 *                                 lets a reloaded conversation still show that an image was never
 *                                 read. The description text itself is deliberately not exposed —
 *                                 it is a paragraph of prose per image
 * @param descriptionFailureReason why {@code described} is false, null when it is true or when the
 *                                 image has not been through the vision pass yet
 */
public record ChatAttachmentSummary(UUID id,
                                    UUID chatMessageId,
                                    String fileName,
                                    String description,
                                    String contentType,
                                    long fileSizeBytes,
                                    boolean described,
                                    VisionFailureReason descriptionFailureReason) {
}
