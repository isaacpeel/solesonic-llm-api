package com.solesonic.repository.image;

import com.solesonic.model.image.GeneratedImage;
import com.solesonic.model.image.GeneratedImageSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeneratedImageRepository extends JpaRepository<GeneratedImage, UUID> {

    /**
     * User-scoped for the same reason attachment lookups are: these bytes came out of a free-text
     * prompt somebody typed, so a generated image is readable only by the user who generated it.
     */
    Optional<GeneratedImage> findByIdAndUserId(UUID imageId, UUID userId);

    /**
     * Summaries for a whole conversation, for hydrating history.
     * <p>
     * A constructor expression rather than the entity, because the entity carries the image bytes:
     * a conversation with a dozen images would pull tens of megabytes into memory just to render a
     * list of references. {@code imageUrl} is filled in afterwards — JPQL can select columns but
     * cannot build a path.
     */
    @Query("""
            select new com.solesonic.model.image.GeneratedImageSummary(
                       image.id, image.chatMessageId, null, image.prompt, image.model, image.seed,
                       image.width, image.height, image.steps, image.elapsedSeconds,
                       image.fileSizeBytes, image.created)
              from GeneratedImage image
             where image.chatId = :chatId
               and image.chatMessageId is not null
             order by image.created asc
           """)
    List<GeneratedImageSummary> findSummariesByChatId(UUID chatId);

    /**
     * Images produced during one turn, for the {@code done} payload. Bounded by time rather than by
     * message id because the assistant message is written by the chat memory advisor, which does
     * not hand its id back to the stream that is about to finish.
     */
    @Query("""
            select new com.solesonic.model.image.GeneratedImageSummary(
                       image.id, image.chatMessageId, null, image.prompt, image.model, image.seed,
                       image.width, image.height, image.steps, image.elapsedSeconds,
                       image.fileSizeBytes, image.created)
              from GeneratedImage image
             where image.chatId = :chatId
               and image.created >= :since
             order by image.created asc
           """)
    List<GeneratedImageSummary> findSummariesByChatIdSince(UUID chatId, ZonedDateTime since);

    /**
     * Claims every image already generated for this chat but not yet attached to a message. The
     * conditional update is the authoritative check — an image claimed by an earlier turn simply
     * will not match.
     */
    @Modifying
    @Query("""
            update GeneratedImage image
               set image.chatMessageId = :chatMessageId
             where image.chatId = :chatId
               and image.chatMessageId is null
           """)
    int bind(UUID chatId, UUID chatMessageId);
}
