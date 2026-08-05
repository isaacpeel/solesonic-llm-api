package com.solesonic.model.chat.attachment;

import java.util.UUID;

/**
 * A described image attachment, carrying exactly what the prompt block renders. Kept separate from
 * {@link ChatAttachmentSummary} so that listing a user's chats does not pull every generated
 * description into the response payload.
 *
 * @param description       the client-supplied note, or {@code null}
 * @param visionDescription the text produced by the vision model
 */
public record ChatAttachmentDescription(UUID chatMessageId,
                                        String fileName,
                                        String description,
                                        String visionDescription) {
}
