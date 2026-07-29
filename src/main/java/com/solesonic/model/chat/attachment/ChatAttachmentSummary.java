package com.solesonic.model.chat.attachment;

import java.util.UUID;

public record ChatAttachmentSummary(UUID id,
                                    UUID chatMessageId,
                                    String fileName,
                                    String description,
                                    String contentType,
                                    long fileSizeBytes) {
}
