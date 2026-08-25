package com.solesonic.repository.chat;

import com.solesonic.model.chat.group.ChatGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatGroupRepository extends JpaRepository<ChatGroup, UUID> {
    /**
     * Every group of one user, in the order a sidebar renders them: hand-placed sections first, then
     * everything else by name, with the id as a final tiebreaker so two groups sharing a rank and a
     * name do not swap places between requests.
     * <p>
     * "nulls last" is what makes an unplaced group keep its old behaviour: a section nobody has
     * arranged sorts by name exactly as it did before ordering existed, and a newly created one
     * arrives among those rather than at the top of the arrangement. The order lives here rather
     * than in a caller-supplied {@code Sort} for the same reason the chat listings keep theirs.
     */
    @Query("""
            from ChatGroup chatGroup
            where chatGroup.userId = :userId
            order by chatGroup.sortOrder asc nulls last, chatGroup.name asc, chatGroup.id asc
            """)
    List<ChatGroup> findByUserId(UUID userId);

    /**
     * User-scoped for the same reason {@code ChatRepository.findByIdAndUserId} is: a caller must own
     * the group it is reading or filing a conversation into, and enforcing that at the query is what
     * makes another user's group indistinguishable from one that does not exist.
     */
    Optional<ChatGroup> findByIdAndUserId(UUID chatGroupId, UUID userId);
}
