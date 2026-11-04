package com.solesonic.repository.chat;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.attachment.ChatAttachmentIndexState;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.model.chat.attachment.ExtractionFailureReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, UUID> {

    Optional<ChatAttachment> findByIdAndUserId(UUID attachmentId, UUID userId);

    /**
     * Loads whole rows — image bytes included — for the attachments named by one send. User-scoped
     * for the same reason {@link #findByIdAndUserId} is, and ordered so that image numbering in the
     * prompt is stable across turns; {@code attachmentIds} arrives as an unordered set.
     */
    List<ChatAttachment> findByIdInAndUserIdOrderByCreatedAsc(Set<UUID> attachmentIds, UUID userId);

    /**
     * Filtering on {@code chatId} restricts this to bound rows, and the
     * {@code chat_attachment_bound_together} check constraint then guarantees a non-null
     * {@code chatMessageId} — which is what makes grouping by it safe for callers.
     */
    @Query("""
            select new com.solesonic.model.chat.attachment.ChatAttachmentDescription(
                       attachment.chatMessageId, attachment.fileName,
                       attachment.description, attachment.visionDescription)
              from ChatAttachment attachment
             where attachment.chatId = :chatId
               and attachment.visionDescription is not null
             order by attachment.created asc
           """)
    List<ChatAttachmentDescription> findDescriptionsByChatId(UUID chatId);

    /**
     * {@code described} is derived rather than stored: a non-null {@code visionDescription} is the
     * one authoritative record that the vision pass produced something. The description text is not
     * selected — it is a paragraph per image, and callers only need the flag.
     * <p>
     * {@code indexed} is derived the same way from {@code chunkCount}, which is the document-side
     * record that extraction produced something retrievable.
     */
    @Query("""
            select new com.solesonic.model.chat.attachment.ChatAttachmentSummary(
                       attachment.id, attachment.chatMessageId, attachment.fileName,
                       attachment.description, attachment.contentType, attachment.fileSizeBytes,
                       case when attachment.visionDescription is not null then true else false end,
                       attachment.visionFailureReason,
                       case when attachment.chunkCount is not null then true else false end,
                       attachment.extractionFailureReason)
              from ChatAttachment attachment
             where attachment.chatId = :chatId
             order by attachment.created asc
           """)
    List<ChatAttachmentSummary> findSummariesByChatId(UUID chatId);

    @Query("""
            select new com.solesonic.model.chat.attachment.ChatAttachmentSummary(
                       attachment.id, attachment.chatMessageId, attachment.fileName,
                       attachment.description, attachment.contentType, attachment.fileSizeBytes,
                       case when attachment.visionDescription is not null then true else false end,
                       attachment.visionFailureReason,
                       case when attachment.chunkCount is not null then true else false end,
                       attachment.extractionFailureReason)
              from ChatAttachment attachment
             where attachment.chatId = :chatId
               and attachment.id in :attachmentIds
             order by attachment.created asc
           """)
    List<ChatAttachmentSummary> findSummariesByChatIdAndIdIn(UUID chatId, Collection<UUID> attachmentIds);

    @Modifying
    @Query("""
            update ChatAttachment attachment
               set attachment.chatId = :chatId,
                   attachment.chatMessageId = :chatMessageId
             where attachment.id in :attachmentIds
               and attachment.userId = :userId
               and attachment.chatMessageId is null
           """)
    int bind(Set<UUID> attachmentIds, UUID userId, UUID chatId, UUID chatMessageId);

    @Modifying
    @Query("""
            delete from ChatAttachment attachment
             where attachment.chatMessageId is null and attachment.created < :cutoff
           """)
    int deleteStagedOlderThan(ZonedDateTime cutoff);

    /**
     * Every attachment bound to one conversation. Deleting a chat has to take these with it: the
     * rows carry image bytes, and there is no foreign key that would remove them on its own.
     * Staged attachments are untouched — they have no {@code chatId} yet, and the sweep owns them.
     */
    @Modifying
    @Query("""
            delete from ChatAttachment attachment
             where attachment.chatId = :chatId
           """)
    int deleteByChatId(UUID chatId);

    /**
     * The attachment ids of one message, read before the bulk delete below so each one's
     * {@code ingested_document} row and vector chunks can be swept individually — neither carries a
     * {@code chatMessageId} of its own to bulk-delete by.
     */
    @Query("""
            select attachment.id
              from ChatAttachment attachment
             where attachment.chatMessageId = :chatMessageId
           """)
    List<UUID> findIdsByChatMessageId(UUID chatMessageId);

    /**
     * Every attachment bound to one message. Deleting a message has to take these with it for the
     * same reason {@link #deleteByChatId} does: image bytes with no foreign key to remove them.
     */
    @Modifying
    @Query("""
            delete from ChatAttachment attachment
             where attachment.chatMessageId = :chatMessageId
           """)
    int deleteByChatMessageId(UUID chatMessageId);

    /**
     * The index lifecycle of one attachment, without its bytes.
     */
    @Query("""
            select new com.solesonic.model.chat.attachment.ChatAttachmentIndexState(
                       attachment.id, attachment.userId, attachment.chatId, attachment.contentType,
                       attachment.fileSizeBytes, attachment.chunkCount, attachment.extractionFailureReason,
                       attachment.indexDetached, attachment.indexClaimedAt, attachment.indexAttemptedAt)
              from ChatAttachment attachment
             where attachment.id = :attachmentId
           """)
    Optional<ChatAttachmentIndexState> findIndexState(UUID attachmentId);

    /**
     * The index lifecycle of every attachment bound to one conversation, images included — the
     * caller filters by content type with the same rule the send path uses.
     */
    @Query("""
            select new com.solesonic.model.chat.attachment.ChatAttachmentIndexState(
                       attachment.id, attachment.userId, attachment.chatId, attachment.contentType,
                       attachment.fileSizeBytes, attachment.chunkCount, attachment.extractionFailureReason,
                       attachment.indexDetached, attachment.indexClaimedAt, attachment.indexAttemptedAt)
              from ChatAttachment attachment
             where attachment.chatId = :chatId
             order by attachment.created asc
           """)
    List<ChatAttachmentIndexState> findIndexStatesByChatId(UUID chatId);

    /**
     * Every attachment, across all conversations, that claims to be indexed and has not left its
     * conversation — the population the consistency report verifies.
     */
    @Query("""
            select new com.solesonic.model.chat.attachment.ChatAttachmentIndexState(
                       attachment.id, attachment.userId, attachment.chatId, attachment.contentType,
                       attachment.fileSizeBytes, attachment.chunkCount, attachment.extractionFailureReason,
                       attachment.indexDetached, attachment.indexClaimedAt, attachment.indexAttemptedAt)
              from ChatAttachment attachment
             where attachment.chatId is not null
               and attachment.chunkCount is not null
               and attachment.indexDetached = false
             order by attachment.created asc
           """)
    List<ChatAttachmentIndexState> findClaimingIndexed();

    /**
     * Takes the indexing lease, if nobody holds a live one and the attachment has not left its
     * conversation. The conditional update is the whole guarantee: two workers racing for the same
     * attachment cannot both see {@code 1}.
     * <p>
     * {@code chunkCount} is cleared with it, so nothing reports the attachment as indexed while its
     * chunks are being replaced; a successful ingest sets it again.
     * <p>
     * {@code observedAttemptedAt} is the {@code indexAttemptedAt} the caller saw when it decided the
     * attachment needed indexing. Every claim moves it, so if another worker claimed (and perhaps
     * finished) in between, this claim fails instead of redoing that worker's work on a stale
     * decision. {@code now} is the lease token: release and the final writes match on it, so it must
     * already be at the column's precision (microseconds).
     * <p>
     * Only called through {@code ChatAttachmentService}'s short transactions: {@code clearAutomatically}
     * would detach any entity a longer caller still held.
     * <p>
     * Two queries rather than one null-safe comparison: Postgres cannot infer the type of a parameter
     * that only appears in {@code :p is null}, and rejects the statement.
     *
     * @return 1 when claimed, 0 when another worker holds the lease, claimed since the caller looked,
     *         or the attachment is detached or gone
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatAttachment attachment
               set attachment.indexClaimedAt = :now,
                   attachment.indexAttemptedAt = :now,
                   attachment.chunkCount = null
             where attachment.id = :attachmentId
               and attachment.indexDetached = false
               and (attachment.indexClaimedAt is null or attachment.indexClaimedAt < :staleBefore)
               and attachment.indexAttemptedAt = :observedAttemptedAt
           """)
    int claimIndexing(UUID attachmentId,
                      ZonedDateTime now,
                      ZonedDateTime staleBefore,
                      ZonedDateTime observedAttemptedAt);

    /**
     * {@link #claimIndexing} for a caller that observed no attempt at all.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatAttachment attachment
               set attachment.indexClaimedAt = :now,
                   attachment.indexAttemptedAt = :now,
                   attachment.chunkCount = null
             where attachment.id = :attachmentId
               and attachment.indexDetached = false
               and (attachment.indexClaimedAt is null or attachment.indexClaimedAt < :staleBefore)
               and attachment.indexAttemptedAt is null
           """)
    int claimIndexingNeverAttempted(UUID attachmentId, ZonedDateTime now, ZonedDateTime staleBefore);

    /**
     * Releases the lease only if it is still the caller's: a worker that outlived the stale cutoff
     * must not wipe the lease of the worker that took over from it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatAttachment attachment
               set attachment.indexClaimedAt = null
             where attachment.id = :attachmentId
               and attachment.indexClaimedAt = :claimedAt
           """)
    int releaseIndexing(UUID attachmentId, ZonedDateTime claimedAt);

    /**
     * Records the outcome of an ingest only if the caller still holds the lease it ran under. A
     * worker whose lease was taken over has its result discarded; the new holder's stands.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatAttachment attachment
               set attachment.chunkCount = :chunkCount,
                   attachment.extractionFailureReason = null
             where attachment.id = :attachmentId
               and attachment.indexClaimedAt = :claimedAt
           """)
    int saveChunkCountUnderLease(UUID attachmentId, int chunkCount, ZonedDateTime claimedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ChatAttachment attachment
               set attachment.extractionFailureReason = :extractionFailureReason
             where attachment.id = :attachmentId
               and attachment.indexClaimedAt = :claimedAt
           """)
    int saveExtractionFailureUnderLease(UUID attachmentId,
                                        ExtractionFailureReason extractionFailureReason,
                                        ZonedDateTime claimedAt);

    /**
     * Records that a document left its conversation on purpose, so it is never re-indexed there.
     * Leaves {@code chunkCount} alone: the attachment's history still says it was indexed.
     * <p>
     * Runs inside the caller's promote/delete transaction, so it does not clear the persistence
     * context: that would detach the {@code IngestedDocument} the caller is still modifying.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ChatAttachment attachment
               set attachment.indexDetached = true
             where attachment.id = :attachmentId
           """)
    int markIndexDetached(UUID attachmentId);
}
