package com.solesonic.model.image;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The whole input surface of image generation. Size, steps, and the seed are fixed by the image
 * server and are deliberately not caller-tunable.
 *
 * @param tool                   deprecated and ignored: {@code generate_image} is always called, and
 *                               the MCP server chooses the workflow
 * @param referenceAttachmentIds staged chat attachments to guide the image, in order; optional, at
 *                               most three. Each must be one of the caller's own PNG, JPEG or WebP
 *                               attachments
 */
public record GenerateImageRequest(String prompt, String tool, List<UUID> referenceAttachmentIds) {

    public GenerateImageRequest {
        referenceAttachmentIds = referenceAttachmentIds == null
                ? List.of()
                : referenceAttachmentIds.stream().filter(Objects::nonNull).toList();
    }
}
