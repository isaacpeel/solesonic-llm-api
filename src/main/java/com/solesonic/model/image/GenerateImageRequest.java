package com.solesonic.model.image;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The whole input surface of image generation. Size, steps, and the seed are fixed by the image
 * server and are deliberately not caller-tunable.
 *
 * @param tool                   the image tool to use, one of {@code GET /images/tools}; may be left
 *                               out only when exactly one image tool exists
 * @param referenceAttachmentIds staged chat attachments to guide the image, in slot order; optional.
 *                               Each must be one of the caller's own PNG, JPEG or WebP attachments
 */
public record GenerateImageRequest(String prompt, String tool, List<UUID> referenceAttachmentIds) {

    public GenerateImageRequest {
        referenceAttachmentIds = referenceAttachmentIds == null
                ? List.of()
                : referenceAttachmentIds.stream().filter(Objects::nonNull).toList();
    }
}
