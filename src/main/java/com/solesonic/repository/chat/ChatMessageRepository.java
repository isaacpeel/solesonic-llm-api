package com.solesonic.repository.chat;

import com.solesonic.model.chat.history.ChatMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.domain.Limit;
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
     * The messages of one conversation that memory reflection has not read yet, oldest first.
     */
    List<ChatMessage> findByChatIdAndTimestampAfterOrderByTimestampAsc(UUID chatId, ZonedDateTime after);

    /**
     * The newest message of one type in a conversation — for {@code USER}, the in-flight message,
     * which is written before the turn reaches any tool.
     */
    Optional<ChatMessage> findFirstByChatIdAndMessageTypeOrderByTimestampDesc(UUID chatId, MessageType messageType);

    /**
     * The newest messages of one type in a conversation, newest first — for {@code USER}, what memory
     * retrieval reads as the conversation so far.
     */
    List<ChatMessage> findByChatIdAndMessageTypeOrderByTimestampDesc(UUID chatId, MessageType messageType, Limit limit);

    /**
     * The row the chat memory advisor just wrote for this turn — found by timing, not id, because
     * {@link com.solesonic.config.chat.DatabaseChatMemory} never hands the id back to the caller
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

    /**
     * Copies the most recent {@code limit} {@code USER} and {@code ASSISTANT} messages of one chat into
     * another, in one statement, keeping their timestamps and inserting them oldest first (ties on the
     * timestamp broken by id, so a copy is the same every time). The window starts at its first
     * {@code USER} message: an {@code ASSISTANT} reply at its head answers a question the window cut
     * off, and a window with no {@code USER} message at all copies nothing. Only the text and what
     * the model server reported about each turn are copied: attachments, generated images,
     * elicitation, progress and commands stay behind. {@code USER} rows are attributed to
     * {@code senderUserId}.
     * <p>
     * Native, because the alternative is a {@code save} per row, and a save reloads the chat.
     *
     * @return the number of rows written
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into chat_message (id, chat_id, sender_user_id, timestamp, message_type, message,
                                      response_metadata, response_metadata_calls)
            select gen_random_uuid(), :targetChatId,
                   case when recent.message_type = 'USER' then cast(:senderUserId as uuid) end,
                   recent.timestamp, recent.message_type, recent.message,
                   recent.response_metadata, recent.response_metadata_calls
              from (select limited.timestamp, limited.message_type, limited.message,
                           limited.response_metadata, limited.response_metadata_calls, limited.rn
                      from (select ranked.timestamp, ranked.message_type, ranked.message,
                                   ranked.response_metadata, ranked.response_metadata_calls, ranked.rn,
                                   max(case when ranked.message_type = 'USER' then ranked.rn end) over () as first_user_rn
                              from (select source.timestamp, source.message_type, source.message,
                                           source.response_metadata, source.response_metadata_calls,
                                           row_number() over (order by source.timestamp desc, source.id desc) as rn
                                      from chat_message source
                                     where source.chat_id = :sourceChatId
                                       and source.message_type in ('USER', 'ASSISTANT')) ranked
                             where ranked.rn <= :limit) limited
                     where limited.rn <= limited.first_user_rn) recent
             order by recent.rn desc
            """)
    int forkRecent(UUID sourceChatId, UUID targetChatId, UUID senderUserId, int limit);
}
