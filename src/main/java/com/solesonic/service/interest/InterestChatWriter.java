package com.solesonic.service.interest;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.service.chat.ChatMessageService;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Writes a news chat without a request. {@code ChatService} and {@code ChatGroupService} read the
 * owner from the request-scoped {@code UserRequestContext}, which a scheduled run does not have, so
 * this writes through {@link ChatRepository} with the owner passed explicitly, the way
 * {@code RedisStreamingChatService.create} does. The owner is always the agent row's.
 */
@Component
public class InterestChatWriter {
    private final ChatRepository chatRepository;
    private final ChatMessageService chatMessageService;

    public InterestChatWriter(ChatRepository chatRepository, ChatMessageService chatMessageService) {
        this.chatRepository = chatRepository;
        this.chatMessageService = chatMessageService;
    }

    /**
     * One chat holding one {@code ASSISTANT} message. Joins the delivery's transaction, so the chat,
     * its message and the update row pointing at it exist together or not at all.
     *
     * @param chatGroupId the group to file it in, already checked as the owner's, or null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Chat write(UUID userId, String name, UUID chatGroupId, String text, ZonedDateTime now) {
        Chat chat = new Chat();
        chat.setUserId(userId);
        chat.setName(name);
        chat.setChatGroupId(chatGroupId);
        chat.setTimestamp(now);

        Chat saved = chatRepository.save(chat);

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(saved.getId());
        chatMessage.setMessageType(MessageType.ASSISTANT);
        chatMessage.setMessage(text);
        chatMessageService.save(chatMessage);

        return saved;
    }
}
