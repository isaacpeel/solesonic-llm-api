package com.solesonic.repository.image;

import com.solesonic.model.image.GeneratedImage;
import com.solesonic.model.image.GeneratedImageSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
                       image.id, image.userId, image.chatMessageId, null, image.name, image.prompt,
                       image.model, image.seed, image.width, image.height, image.steps,
                       image.elapsedSeconds, image.fileSizeBytes, image.created)
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
                       image.id, image.userId, image.chatMessageId, null, image.name, image.prompt,
                       image.model, image.seed, image.width, image.height, image.steps,
                       image.elapsedSeconds, image.fileSizeBytes, image.created)
              from GeneratedImage image
             where image.chatId = :chatId
               and image.created >= :since
             order by image.created asc
           """)
    List<GeneratedImageSummary> findSummariesByChatIdSince(UUID chatId, ZonedDateTime since);

    /**
     * One user's own images, newest first, for the self-service management listing.
     */
    @Query(value = """
            select new com.solesonic.model.image.GeneratedImageSummary(
                       image.id, image.userId, image.chatMessageId, null, image.name, image.prompt,
                       image.model, image.seed, image.width, image.height, image.steps,
                       image.elapsedSeconds, image.fileSizeBytes, image.created)
              from GeneratedImage image
             where image.userId = :userId
             order by image.created desc
           """,
            countQuery = """
                    select count(image)
                      from GeneratedImage image
                     where image.userId = :userId
                   """)
    Page<GeneratedImageSummary> findSummaryPageByUserId(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Every user's images, newest first, for the {@code image-admin} listing.
     */
    @Query(value = """
            select new com.solesonic.model.image.GeneratedImageSummary(
                       image.id, image.userId, image.chatMessageId, null, image.name, image.prompt,
                       image.model, image.seed, image.width, image.height, image.steps,
                       image.elapsedSeconds, image.fileSizeBytes, image.created)
              from GeneratedImage image
             order by image.created desc
           """,
            countQuery = """
                    select count(image)
                      from GeneratedImage image
                   """)
    Page<GeneratedImageSummary> findSummaryPage(Pageable pageable);

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

    /**
     * Every image generated inside one conversation, bound or not. Deleting a chat takes these with
     * it for the same reason it takes attachments: megabytes of bytes per row and no foreign key to
     * remove them. Images from explicit {@code /images} generation carry no {@code chatId} and are
     * never matched.
     */
    @Modifying
    @Query("""
            delete from GeneratedImage image
             where image.chatId = :chatId
           """)
    int deleteByChatId(UUID chatId);
}
