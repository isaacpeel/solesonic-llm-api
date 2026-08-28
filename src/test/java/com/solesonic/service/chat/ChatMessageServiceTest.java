package com.solesonic.service.chat;

import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.ModelCallMetadata;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the trailing-USER rule in {@link ChatMessageService#findByChatId(UUID)}.
 * <p>
 * The in-flight user message is persisted before the stream starts, and the chat memory advisor
 * supplies it again as the live user message. If this rule regresses the model sees the current
 * turn twice on every turn — which produces no error, just quietly worse output. That silence is
 * why it gets a dedicated test.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    @SuppressWarnings("unused")
    private ChatRepository chatRepository;

    @Mock
    @SuppressWarnings("unused")
    private UserPreferencesService userPreferencesService;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @InjectMocks
    private ChatMessageService chatMessageService;

    private final UUID chatId = UUID.randomUUID();

    private ChatMessage chatMessage(MessageType messageType, String text) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setId(UUID.randomUUID());
        chatMessage.setChatId(chatId);
        chatMessage.setMessageType(messageType);
        chatMessage.setMessage(text);

        return chatMessage;
    }

    private ChatAttachmentDescription description(UUID chatMessageId, String visionDescription) {
        return new ChatAttachmentDescription(chatMessageId, "screenshot.png", null, visionDescription);
    }

    @Test
    void findByChatIdDropsTrailingUserMessage() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                chatMessage(MessageType.USER, "first question"),
                chatMessage(MessageType.ASSISTANT, "first answer"),
                chatMessage(MessageType.USER, "in-flight question")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo("first question");
        assertThat(messages.getLast()).isInstanceOf(AssistantMessage.class);
        assertThat(messages.getLast().getText()).isEqualTo("first answer");
    }

    @Test
    void findByChatIdKeepsTrailingAssistantMessage() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                chatMessage(MessageType.USER, "question"),
                chatMessage(MessageType.ASSISTANT, "answer")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).hasSize(2);
        assertThat(messages.getLast()).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void findByChatIdReturnsEmptyForSingleUserMessage() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                chatMessage(MessageType.USER, "the only turn so far")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).isEmpty();
    }

    /**
     * The description is replayed as its own message ahead of the turn it belongs to — the same
     * shape PromptService builds for the live turn, and the shape that keeps the retrieval advisor
     * from folding image context into a user message and burying it under retrieved documents.
     */
    @Test
    void findByChatIdReplaysImageDescriptionsAsTheirOwnMessage() {
        ChatMessage withImage = chatMessage(MessageType.USER, "what is this?");

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                withImage,
                chatMessage(MessageType.ASSISTANT, "a login screen")));
        when(chatAttachmentService.descriptions(chatId))
                .thenReturn(List.of(description(withImage.getId(), "a login form with two fields")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).hasSize(3);

        assertThat(messages.getFirst().getText())
                .contains("a login form with two fields")
                .doesNotContain("what is this?");

        assertThat(messages.get(1).getText()).isEqualTo("what is this?");
    }

    @Test
    void findByChatIdLeavesUserTurnsWithoutAttachmentsAlone() {
        ChatMessage withImage = chatMessage(MessageType.USER, "what is this?");
        ChatMessage withoutImage = chatMessage(MessageType.USER, "and this?");

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                withImage,
                withoutImage,
                chatMessage(MessageType.ASSISTANT, "two screens")));
        when(chatAttachmentService.descriptions(chatId))
                .thenReturn(List.of(description(withImage.getId(), "a login form")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        //Context message, the turn it describes, the turn without an image, then the answer.
        assertThat(messages).extracting(Message::getText)
                .containsSubsequence("what is this?", "and this?");

        assertThat(messages).extracting(Message::getText)
                .noneMatch(_ -> false);
    }

    /**
     * The in-flight turn's descriptions are injected by the prompt path, not here. If this row were
     * kept, the current turn's image context would reach the model twice.
     */
    @Test
    void findByChatIdDoesNotReplayDescriptionsForTheInFlightTurn() {
        ChatMessage inFlight = chatMessage(MessageType.USER, "in-flight question");

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                chatMessage(MessageType.USER, "first question"),
                chatMessage(MessageType.ASSISTANT, "first answer"),
                inFlight));
        when(chatAttachmentService.descriptions(chatId))
                .thenReturn(List.of(description(inFlight.getId(), "a login form")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(Message::getText)
                .noneMatch(text -> text != null && text.contains("a login form"));
    }

    @Test
    void findByChatIdReturnsEmptyForEmptyHistory() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of());

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).isEmpty();
    }

    /**
     * The totals and the breakdown are two columns, and a reader must never find one without the
     * other — so both are written on the single row this locates, in the one transaction.
     */
    @Test
    void updateResponseMetadataWritesTotalsAndBreakdownTogether() {
        ChatMessage assistantMessage = chatMessage(MessageType.ASSISTANT, "the answer");
        ZonedDateTime turnStarted = ZonedDateTime.now();

        when(chatMessageRepository
                .findFirstByChatIdAndMessageTypeAndTimestampGreaterThanEqualOrderByTimestampDesc(
                        chatId, MessageType.ASSISTANT, turnStarted))
                .thenReturn(Optional.of(assistantMessage));

        List<ModelCallMetadata> calls = List.of(
                new ModelCallMetadata("qwen3-8b", "chatcmpl-1", null, "tool_calls", 1042, 88, 1130, null, null, null),
                new ModelCallMetadata("qwen3-8b", "chatcmpl-2", null, "stop", 1380, 165, 1545, null, null, null));
        ResponseMetadata responseMetadata = ResponseMetadata.of("qwen3-8b", "chatcmpl-2", null, "stop", calls);

        chatMessageService.updateResponseMetadata(chatId, turnStarted, responseMetadata, calls);

        assertThat(assistantMessage.getResponseMetadata()).isEqualTo(responseMetadata);
        assertThat(assistantMessage.getResponseMetadataCalls()).isEqualTo(calls);
        verify(chatMessageRepository).save(assistantMessage);
    }

    /**
     * A turn whose assistant row cannot be located must not throw: the answer has already streamed to
     * the user, and losing its token accounting is not worth failing the turn over.
     */
    @Test
    void updateResponseMetadataIsSilentWhenNoAssistantRowMatches() {
        ZonedDateTime turnStarted = ZonedDateTime.now();

        when(chatMessageRepository
                .findFirstByChatIdAndMessageTypeAndTimestampGreaterThanEqualOrderByTimestampDesc(
                        chatId, MessageType.ASSISTANT, turnStarted))
                .thenReturn(Optional.empty());

        List<ModelCallMetadata> calls = List.of(
                new ModelCallMetadata("qwen3-8b", "chatcmpl-1", null, "stop", 10, 2, 12, null, null, null));

        chatMessageService.updateResponseMetadata(chatId, turnStarted,
                ResponseMetadata.of("qwen3-8b", "chatcmpl-1", null, "stop", calls), calls);

        verify(chatMessageRepository, never()).save(any(ChatMessage.class));
    }
}
