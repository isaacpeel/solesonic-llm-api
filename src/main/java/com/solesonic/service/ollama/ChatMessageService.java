package com.solesonic.service.ollama;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.ollama.ChatMessageRepository;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.util.AttachmentContextFormatter;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service()
public class ChatMessageService {
    private static final Logger log =  LoggerFactory.getLogger(ChatMessageService.class);
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRepository chatRepository;
    private final UserPreferencesService userPreferencesService;
    private final ChatAttachmentService chatAttachmentService;

    public ChatMessageService(ChatMessageRepository chatMessageRepository,
                              ChatRepository chatRepository,
                              UserPreferencesService userPreferencesService,
                              ChatAttachmentService chatAttachmentService) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatRepository = chatRepository;
        this.userPreferencesService = userPreferencesService;
        this.chatAttachmentService = chatAttachmentService;
    }

    public ChatMessage save(ChatMessage message) {
        UUID chatId = message.getChatId();

        log.debug("Saving chat message with id {}", chatId);

        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new IllegalStateException("Chat not found: " + chatId));

        //Routed through the service, not the repository, so a user's first-ever message
        //self-heals a missing preferences row instead of throwing.
        UserPreferences userPreferences = userPreferencesService.get(chat.getUserId());

        String chatModel = userPreferences.getModel();
        message.setModel(chatModel);
        message.setTimestamp(ZonedDateTime.now());

        return chatMessageRepository.save(message);
    }

    /**
     * Persists the in-flight user message before the stream starts, so its id is known and can be
     * published on the {@code init} event.
     * <p>
     * This is deliberately the caller's job rather than the chat memory advisor's: the advisor never
     * runs on the A2A route, so user messages were previously not persisted there at all.
     * {@link com.solesonic.config.olllama.DatabaseChatMemory} skips {@code USER} messages to avoid
     * saving them twice.
     */
    @Transactional
    public ChatMessage saveUserMessage(UUID chatId, UUID userId, ChatRequest chatRequest) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(MessageType.USER);
        chatMessage.setMessage(chatRequest.chatMessage());

        ChatMessage saved = save(chatMessage);

        //Inside the transaction on purpose: a turn that cannot claim its attachments must not
        //persist a message either.
        chatAttachmentService.bind(userId, chatId, saved.getId(), chatRequest.attachmentIds());

        return saved;
    }

    public void updateElicitationResponse(UUID chatId, UUID elicitationId, Map<String, Object> elicitationResponse) {
        chatMessageRepository.findByChatIdAndElicitationId(chatId, elicitationId)
                .ifPresent(chatMessage -> {
                    chatMessage.setElicitationResponse(elicitationResponse);
                    chatMessageRepository.save(chatMessage);
                });
    }

    /**
     * Attaches token usage and timing to the assistant message {@link com.solesonic.config.olllama.DatabaseChatMemory}
     * already wrote for this turn. This has to be an update rather than something the advisor sets
     * directly: {@code responseMetadata} isn't final until the whole stream completes, which is after
     * that row is saved. {@code since} is the turn's start time, not the message's — the caller has
     * no other way to name the row it wants, because the advisor never hands the id back.
     */
    @Transactional
    public void updateResponseMetadata(UUID chatId, ZonedDateTime since, ResponseMetadata responseMetadata) {
        chatMessageRepository
                .findFirstByChatIdAndMessageTypeAndTimestampGreaterThanEqualOrderByTimestampDesc(chatId, MessageType.ASSISTANT, since)
                .ifPresent(chatMessage -> {
                    chatMessage.setResponseMetadata(responseMetadata);
                    chatMessageRepository.save(chatMessage);
                });
    }

    public List<Message> findByChatId(UUID chatId) {
        List<ChatMessage> chatMessages = chatMessageRepository.findByChatId(chatId);

        // The in-flight user message is persisted before the stream starts, and the chat memory
        // advisor supplies it again as the live user message. Without dropping it here the model
        // would see the current turn twice, every turn.
        if (CollectionUtils.isNotEmpty(chatMessages)
                && chatMessages.getLast().getMessageType() == MessageType.USER) {
            chatMessages = chatMessages.subList(0, chatMessages.size() - 1);
        }

        if(CollectionUtils.isNotEmpty(chatMessages)) {
            List<Message> messages = new ArrayList<>(chatMessages.size());

            // Image context has to be replayed, or a follow-up question about an attached image
            // reaches the model with no idea an image was ever involved. One query per chat, not
            // per message; the descriptions were generated when the image was first sent, so this
            // never calls the vision model.
            Map<UUID, List<ChatAttachmentDescription>> descriptionsByMessageId = chatAttachmentService
                    .descriptions(chatId)
                    .stream()
                    .collect(Collectors.groupingBy(ChatAttachmentDescription::chatMessageId));

            for(ChatMessage chatMessage : chatMessages) {
                if (chatMessage.getProgressData() != null) {
                    continue;
                }

                // Remove <think>...</think> tags from message
                String messageText = chatMessage.getMessage();

                Message message;
                switch (chatMessage.getMessageType()) {
                    case USER -> {
                        assert messageText != null;

                        // Adjacent to the user message it describes, not merged into it — the same
                        // shape the live turn builds in PromptService, so a replayed turn and the
                        // turn that produced it look identical to the model.
                        String imageContext = AttachmentContextFormatter.context(
                                descriptionsByMessageId.getOrDefault(chatMessage.getId(), List.of()));

                        if (imageContext != null) {
                            messages.add(new UserMessage(imageContext));
                        }

                        message = new UserMessage(messageText);
                    }
                    case ASSISTANT -> {
                        assert messageText != null;
                        message = new AssistantMessage(messageText);
                    }
                    default -> {
                        assert messageText != null;
                        message = new SystemMessage(messageText);
                    }
                }

                messages.add(message);
            }

            return messages;
        }

        return List.of();
    }
}
