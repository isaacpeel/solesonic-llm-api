package com.solesonic.repository.chat;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
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
     */
    @Query("""
            select new com.solesonic.model.chat.attachment.ChatAttachmentSummary(
                       attachment.id, attachment.chatMessageId, attachment.fileName,
                       attachment.description, attachment.contentType, attachment.fileSizeBytes,
                       case when attachment.visionDescription is not null then true else false end,
                       attachment.visionFailureReason)
              from ChatAttachment attachment
             where attachment.chatId = :chatId
             order by attachment.created asc
           """)
    List<ChatAttachmentSummary> findSummariesByChatId(UUID chatId);

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
}
