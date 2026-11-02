package com.solesonic.service.image;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Which of the named attachments can be sent to the image service as reference images.
 */
@Component
public class ReferenceImageResolver {

    /**
     * What the image service detects from the bytes. GIF is a chat attachment type but not one it
     * accepts.
     */
    static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private final ChatAttachmentService chatAttachmentService;

    public ReferenceImageResolver(ChatAttachmentService chatAttachmentService) {
        this.chatAttachmentService = chatAttachmentService;
    }

    /**
     * The named attachments that can be sent, in the order they were named: the repository returns
     * rows by creation time, and the order is the caller's. Ownership is enforced by the query, so an
     * id belonging to someone else simply does not come back.
     */
    public List<ChatAttachment> usableAttachments(UUID userId, Collection<UUID> attachmentIds) {
        if (userId == null || attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }

        List<UUID> requested = List.copyOf(new LinkedHashSet<>(attachmentIds));

        return chatAttachmentService.attachments(userId, new LinkedHashSet<>(requested)).stream()
                .filter(ReferenceImageResolver::isUsable)
                .sorted(Comparator.comparingInt(attachment -> requested.indexOf(attachment.getId())))
                .toList();
    }

    private static boolean isUsable(ChatAttachment attachment) {
        return attachment.getContentType() != null
                && SUPPORTED_CONTENT_TYPES.contains(attachment.getContentType().toLowerCase(Locale.ROOT))
                && attachment.getFileData() != null
                && attachment.getFileData().length > 0;
    }
}
