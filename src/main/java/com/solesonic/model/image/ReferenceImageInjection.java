package com.solesonic.model.image;

import java.util.List;
import java.util.UUID;

/**
 * A tool call's arguments after reference images were injected, and which attachments supplied
 * them — the second half is what a stored image records as its provenance.
 */
public record ReferenceImageInjection(String toolCallInput, List<UUID> attachmentIds) {

    public static ReferenceImageInjection none(String toolCallInput) {
        return new ReferenceImageInjection(toolCallInput, List.of());
    }
}
