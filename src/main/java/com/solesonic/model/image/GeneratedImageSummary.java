package com.solesonic.model.image;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A stored image and its provenance. Doubles as the terminal {@code complete} frame of an explicit
 * generation, as the image reference carried on a chat turn, and as the body of the metadata
 * endpoint — a client reloading history needs exactly what a client watching the generation was
 * given.
 * <p>
 * The bytes are not here: they are fetched separately from {@code imageUrl}, which is what keeps a
 * conversation's history a few hundred bytes per image rather than a couple of megabytes.
 * <p>
 * {@code prompt} and {@code seed} together are the provenance record — without them a stored image
 * is an orphan, and no one can say which image a support ticket is about. Every field the image
 * server reports is nullable: the tool returns its metadata as a human-readable text block, and a
 * field this API failed to parse is worth losing quietly rather than failing a generation over.
 *
 * @param chatMessageId the assistant turn this image belongs to, or null for an explicit generation
 *                      or one whose turn has not been written yet
 */
public record GeneratedImageSummary(UUID imageId,
                                    UUID chatMessageId,
                                    String imageUrl,
                                    String prompt,
                                    String model,
                                    Long seed,
                                    Integer width,
                                    Integer height,
                                    Integer steps,
                                    Double elapsedSeconds,
                                    long fileSizeBytes,
                                    ZonedDateTime created) implements ImageGenerationEvent {

    /**
     * Fills in the URL on a summary built by a JPQL constructor expression, which can select
     * columns but cannot build a path. Keeping those projections to real columns is what stops a
     * history query from loading every image's bytes.
     */
    public GeneratedImageSummary withImageUrl(String imageUrl) {
        return new GeneratedImageSummary(imageId, chatMessageId, imageUrl, prompt, model, seed,
                width, height, steps, elapsedSeconds, fileSizeBytes, created);
    }

    @Override
    public String eventName() {
        return COMPLETE;
    }
}
