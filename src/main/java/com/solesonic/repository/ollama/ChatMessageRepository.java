package com.solesonic.repository.ollama;

import com.solesonic.model.chat.history.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    @Query("""
            from ChatMessage cm where cm.chatId = :chatId
                        order by cm.timestamp asc
           """)
    List<ChatMessage> findByChatId(UUID chatId);

    Optional<ChatMessage> findByChatIdAndElicitationId(UUID chatId, UUID elicitationId);

    /**
     * Every message of one conversation, in one statement.
     * <p>
     * There is no foreign key from {@code chat_message} to {@code chat}, so nothing in the database
     * removes these when the conversation goes: deleting a chat without this leaves its whole
     * transcript behind, unreachable but still stored.
     */
    @Modifying
    @Query("""
            delete from ChatMessage chatMessage
             where chatMessage.chatId = :chatId
           """)
    int deleteByChatId(UUID chatId);
}
