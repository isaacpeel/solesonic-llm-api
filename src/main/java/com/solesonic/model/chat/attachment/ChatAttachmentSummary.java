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
 * @param indexed                  whether a document attachment's text reached the vector store, so
 *                                 that a reloaded conversation still shows which documents the
 *                                 assistant can actually read. Always false for an image
 * @param extractionFailureReason  why {@code indexed} is false, null when it is true or when the
 *                                 document has not been through the extraction pass yet
 */
public record ChatAttachmentSummary(UUID id,
                                    UUID chatMessageId,
                                    String fileName,
                                    String description,
                                    String contentType,
                                    long fileSizeBytes,
                                    boolean described,
                                    VisionFailureReason descriptionFailureReason,
                                    boolean indexed,
                                    ExtractionFailureReason extractionFailureReason) {
}
