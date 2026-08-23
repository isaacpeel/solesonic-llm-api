package com.solesonic.repository.ollama;

import com.solesonic.model.chat.history.Chat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ChatRepository extends JpaRepository<Chat, UUID> {
    // Newest first, with the id as a tiebreaker. Chats created in the same millisecond would
    // otherwise be ordered arbitrarily per query, which an infinite scroll sees as a row that
    // repeats on one page and never appears on the next. The order lives here rather than in a
    // caller-supplied Sort so that no caller can page this without a deterministic ordering.
    @Query("""
            from Chat chat
            where chat.userId = :userId
            order by chat.timestamp desc, chat.id desc
            """)
    Page<Chat> findByUserId(UUID userId, Pageable pageable);

    /**
     * User-scoped for the same reason generated image lookups are: a caller must own the chat it
     * is modifying, and this is what lets a write be rejected at the query rather than trusted from
     * an id alone.
     */
    Optional<Chat> findByIdAndUserId(UUID chatId, UUID userId);
}
