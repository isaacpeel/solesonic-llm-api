package com.solesonic.repository.chat;

import com.solesonic.model.chat.attachment.ChatAttachment;
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

    @Query("""
            select new com.solesonic.model.chat.attachment.ChatAttachmentSummary(
                       attachment.id, attachment.chatMessageId, attachment.fileName,
                       attachment.description, attachment.contentType, attachment.fileSizeBytes)
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
}
