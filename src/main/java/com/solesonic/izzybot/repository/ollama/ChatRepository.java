package com.solesonic.izzybot.repository.ollama;

import com.solesonic.izzybot.model.chat.history.Chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ChatRepository extends JpaRepository<Chat, UUID> {
    @Query("""
            from Chat chat where chat.userId = :userId
            order by chat.timestamp desc
            """)
    List<Chat> findByUserId(UUID userId);
}
