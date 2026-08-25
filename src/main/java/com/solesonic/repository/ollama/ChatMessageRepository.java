package com.solesonic.repository.ollama;

import com.solesonic.model.chat.history.ChatMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
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
     * The row the chat memory advisor just wrote for this turn — found by timing, not id, because
     * {@link com.solesonic.config.olllama.DatabaseChatMemory} never hands the id back to the caller
     * that knows the turn's {@code responseMetadata}. Mirrors how
     * {@link com.solesonic.service.image.GeneratedImageService#forChatSince} locates this turn's
     * images.
     */
    Optional<ChatMessage> findFirstByChatIdAndMessageTypeAndTimestampGreaterThanEqualOrderByTimestampDesc(
            UUID chatId, MessageType messageType, ZonedDateTime since);

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
