package com.solesonic.repository.chat;

import com.solesonic.model.chat.group.ChatGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatGroupRepository extends JpaRepository<ChatGroup, UUID> {
    /**
     * Every group of one user, in the order a sidebar renders them: by name, with the id as a
     * tiebreaker so two groups sharing a name do not swap places between requests.
     */
    @Query("""
            from ChatGroup chatGroup
            where chatGroup.userId = :userId
            order by chatGroup.name asc, chatGroup.id asc
            """)
    List<ChatGroup> findByUserId(UUID userId);

    /**
     * User-scoped for the same reason {@code ChatRepository.findByIdAndUserId} is: a caller must own
     * the group it is reading or filing a conversation into, and enforcing that at the query is what
     * makes another user's group indistinguishable from one that does not exist.
     */
    Optional<ChatGroup> findByIdAndUserId(UUID chatGroupId, UUID userId);
}
