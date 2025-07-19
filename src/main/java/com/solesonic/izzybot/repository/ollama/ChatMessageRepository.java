package com.solesonic.izzybot.repository.ollama;

import com.solesonic.izzybot.model.chat.history.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    @Query("""
            from ChatMessage cm where cm.chatId = :chatId
                        order by cm.timestamp asc
           """)
    List<ChatMessage> findByChatId(UUID chatId);
}
