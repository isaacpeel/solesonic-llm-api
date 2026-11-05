package com.solesonic.service.chat;

import com.solesonic.model.chat.ChatRequest;
import com.solesonic.model.chat.attachment.ChatAttachmentDescription;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.model.chat.ModelCallMetadata;
import com.solesonic.model.chat.ResponseMetadata;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.memory.UserMemoryService;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
    private ChatRepository chatRepository;

    @Mock
    private UserPreferencesService userPreferencesService;

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private ChatService chatService;

    @Mock
    private GeneratedImageService generatedImageService;

    @Mock
    private UserMemoryService userMemoryService;

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

    private ChatMessage sentBy(UUID senderUserId, String text) {
        ChatMessage chatMessage = chatMessage(MessageType.USER, text);
        chatMessage.setSenderUserId(senderUserId);

        return chatMessage;
    }

    @Test
    void sharedHistoryNamesTheSenderOfEachUserMessageAndLeavesAssistantMessagesAlone() {
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                sentBy(alex, "hello"),
                chatMessage(MessageType.ASSISTANT, "hi Alex"),
                sentBy(sam, "and me?"),
                chatMessage(MessageType.ASSISTANT, "hi Sam"),
                sentBy(unknown, "who am I"),
                chatMessage(MessageType.ASSISTANT, "someone"),
                sentBy(null, "no sender"),
                chatMessage(MessageType.ASSISTANT, "ok"),
                sentBy(alex, "in flight")));

        List<Message> messages = chatMessageService.findByChatId(chatId, Map.of(alex, "Alex", sam, "Sam"));

        assertThat(messages).extracting(Message::getText).containsExactly(
                "Alex: hello", "hi Alex", "Sam: and me?", "hi Sam", "Someone: who am I", "someone", "Someone: no sender", "ok");
    }

    @Test
    void aSharedTranscriptCarriesTheImagesGeneratedInItsReplies() {
        ChatMessage reply = chatMessage(MessageType.ASSISTANT, "here it is");
        GeneratedImageSummary image = new GeneratedImageSummary(UUID.randomUUID(), UUID.randomUUID(), reply.getId(), "/images/x", null,
                "a lighthouse", "model", null, 1024, 1024, null, 2.0, 8, ZonedDateTime.now(), List.of());

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(chatMessage(MessageType.USER, "draw"), reply));
        when(generatedImageService.forChat(chatId)).thenReturn(List.of(image));

        List<ChatMessage> transcript = chatMessageService.sharedTranscript(chatId);

        assertThat(transcript.getLast().getGeneratedImages()).containsExactly(image);
        assertThat(transcript.getFirst().getGeneratedImages()).isEmpty();
    }

    private static ChatAttachmentSummary attachmentOf(UUID chatMessageId, String fileName, String contentType) {
        return new ChatAttachmentSummary(UUID.randomUUID(), chatMessageId, fileName, null, contentType, 12L, false, null, false, null);
    }

    @Test
    void aSharedTranscriptCarriesEveryParticipantsAttachmentsOnTheirMessages() {
        ChatMessage fromAlex = chatMessage(MessageType.USER, "see this");
        ChatMessage fromSam = chatMessage(MessageType.USER, "and this");
        ChatMessage reply = chatMessage(MessageType.ASSISTANT, "ok");
        ChatAttachmentSummary alexsImage = attachmentOf(fromAlex.getId(), "alex.png", "image/png");
        ChatAttachmentSummary samsPdf = attachmentOf(fromSam.getId(), "sam.pdf", "application/pdf");

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(fromAlex, reply, fromSam));
        when(chatAttachmentService.forChat(chatId)).thenReturn(List.of(alexsImage, samsPdf));
        when(generatedImageService.forChat(chatId)).thenReturn(List.of());

        List<ChatMessage> transcript = chatMessageService.sharedTranscript(chatId);

        assertThat(transcript.get(0).getAttachments()).containsExactly(alexsImage);
        assertThat(transcript.get(1).getAttachments()).isEmpty();
        assertThat(transcript.get(2).getAttachments()).containsExactly(samsPdf);
    }

    @Test
    void aSharedTranscriptWithoutAttachmentsHasEmptyListsNotNulls() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(chatMessage(MessageType.USER, "hi")));
        when(chatAttachmentService.forChat(chatId)).thenReturn(List.of());
        when(generatedImageService.forChat(chatId)).thenReturn(List.of());

        assertThat(chatMessageService.sharedTranscript(chatId).getFirst().getAttachments()).isEmpty();
    }

    @Test
    void sharedHistoryKeepsEachSendersImageDescriptionsAheadOfTheirNamedMessage() {
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();
        ChatMessage fromAlex = sentBy(alex, "what is this?");
        ChatMessage fromSam = sentBy(sam, "and this?");

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                fromAlex, chatMessage(MessageType.ASSISTANT, "a login screen"), fromSam,
                chatMessage(MessageType.ASSISTANT, "a chart"), sentBy(alex, "in flight")));
        when(chatAttachmentService.descriptions(chatId)).thenReturn(List.of(
                description(fromAlex.getId(), "a login screen"), description(fromSam.getId(), "a bar chart")));

        List<Message> messages = chatMessageService.findByChatId(chatId, Map.of(alex, "Alex", sam, "Sam"));

        assertThat(messages).extracting(Message::getText).hasSize(6);
        assertThat(messages.get(0).getText()).contains("a login screen");
        assertThat(messages.get(1).getText()).isEqualTo("Alex: what is this?");
        assertThat(messages.get(3).getText()).contains("a bar chart");
        assertThat(messages.get(4).getText()).isEqualTo("Sam: and this?");
    }

    @Test
    void sharedHistoryNamesAMessageSentWithoutText() {
        UUID alex = UUID.randomUUID();
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                sentBy(alex, ""), chatMessage(MessageType.ASSISTANT, "nice picture"), sentBy(alex, "in flight")));

        assertThat(chatMessageService.findByChatId(chatId, Map.of(alex, "Alex"))).extracting(Message::getText)
                .containsExactly("Alex: (sent without any text)", "nice picture");
    }

    @Test
    void aSharedTranscriptKeepsTheTokenAndTimingSummaryButNotTheLiteLlmInternals() {
        ChatMessage reply = chatMessage(MessageType.ASSISTANT, "here it is");
        com.solesonic.model.chat.LiteLlmCallMetadata liteLlm = new com.solesonic.model.chat.LiteLlmCallMetadata(
                "call-1", "gpt", "http://10.0.0.5:8585/v1", 0, 0, 120.0, 3.0);
        reply.setResponseMetadataCalls(List.of(new com.solesonic.model.chat.ModelCallMetadata("qwen", "chatcmpl-1", null, "stop", 10, 20, 30,
                41.5, 1.0, 2.0, 3.0, null, null, null, null, null, null, null, null, liteLlm)));

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(reply));
        when(generatedImageService.forChat(chatId)).thenReturn(List.of());

        com.solesonic.model.chat.ModelCallMetadata call = chatMessageService.sharedTranscript(chatId).getFirst()
                .getResponseMetadataCalls().getFirst();

        assertThat(call.liteLlm()).isNull();
        assertThat(call.totalTokens()).isEqualTo(30);
        assertThat(call.tokensPerSecond()).isEqualTo(41.5);
        assertThat(call.predictedMillis()).isEqualTo(2.0);
    }

    @Test
    void historyWithoutNamesIsUnattributed() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                sentBy(UUID.randomUUID(), "hello"),
                chatMessage(MessageType.ASSISTANT, "hi")));

        assertThat(chatMessageService.findByChatId(chatId)).extracting(Message::getText).containsExactly("hello", "hi");
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

    /**
     * A cancelled turn persists a SYSTEM "Chat canceled." row mid-history for the UI to display.
     * Replaying it to the model would put a system-role message outside position zero, which some
     * chat templates reject outright — so every SYSTEM row (cancellation, progress, failure,
     * elicitation prompt) is UI-only and must never reach the model.
     */
    @Test
    void findByChatIdExcludesSystemMessagesFromReplay() {
        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                chatMessage(MessageType.USER, "first question"),
                chatMessage(MessageType.ASSISTANT, "first answer"),
                chatMessage(MessageType.SYSTEM, "Chat canceled."),
                chatMessage(MessageType.USER, "second question"),
                chatMessage(MessageType.ASSISTANT, "second answer")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).hasSize(4);
        assertThat(messages).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT);
    }

    @Test
    void findByChatIdKeepsTurnsFromOtherCommands() {
        ChatMessage toolRequest = chatMessage(MessageType.USER, "search for cats");
        toolRequest.setCommands(Set.of("search"));

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                toolRequest,
                chatMessage(MessageType.ASSISTANT, "cats found")));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).extracting(Message::getText)
                .containsExactly("search for cats", "cats found");
    }

    @Test
    void saveUserMessageRecordsTheSendsCommands() {
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setUserId(UUID.randomUUID());

        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatMessage saved = chatMessageService.saveUserMessage(chatId, chat.getUserId(),
                new ChatRequest("search for cats", Set.of("search"), Set.of(), null));

        assertThat(saved.getCommands()).containsExactly("search");
        verify(userPreferencesService).get(chat.getUserId());
    }

    @Test
    void saveUserMessageLeavesCommandsNullForAPlainSend() {
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setUserId(UUID.randomUUID());

        when(chatRepository.findById(chatId)).thenReturn(Optional.of(chat));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChatMessage saved = chatMessageService.saveUserMessage(chatId, chat.getUserId(),
                new ChatRequest("hello", Set.of(), Set.of(), null));

        assertThat(saved.getCommands()).isNull();
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
                new ModelCallMetadata("qwen3-8b", "chatcmpl-1", null, "tool_calls", 1042, 88, 1130, null, null, null, null,
                        null, null, null, null, null, null, null, null, null),
                new ModelCallMetadata("qwen3-8b", "chatcmpl-2", null, "stop", 1380, 165, 1545, null, null, null, null,
                        null, null, null, null, null, null, null, null, null));
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
                new ModelCallMetadata("qwen3-8b", "chatcmpl-1", null, "stop", 10, 2, 12, null, null, null, null,
                        null, null, null, null, null, null, null, null, null));

        chatMessageService.updateResponseMetadata(chatId, turnStarted,
                ResponseMetadata.of("qwen3-8b", "chatcmpl-1", null, "stop", calls), calls);

        verify(chatMessageRepository, never()).save(any(ChatMessage.class));
    }

    /**
     * The {@code done} frame is built from scratch rather than from the persisted row, so it has to
     * read the accounting back — the same {@code since} lookup that wrote it, one step later.
     */
    @Test
    void responseMetadataReadsBackWhatTheTurnRecorded() {
        ChatMessage assistantMessage = chatMessage(MessageType.ASSISTANT, "the answer");
        ZonedDateTime turnStarted = ZonedDateTime.now();

        List<ModelCallMetadata> calls = List.of(
                new ModelCallMetadata("qwen3-8b", "chatcmpl-1", null, "stop", 1042, 259, 1301, null, null, null, null,
                        null, null, null, null, null, null, null, null, null));
        ResponseMetadata responseMetadata = ResponseMetadata.of("qwen3-8b", "chatcmpl-1", null, "stop", calls);

        assistantMessage.setResponseMetadata(responseMetadata);

        when(chatMessageRepository
                .findFirstByChatIdAndMessageTypeAndTimestampGreaterThanEqualOrderByTimestampDesc(
                        chatId, MessageType.ASSISTANT, turnStarted))
                .thenReturn(Optional.of(assistantMessage));

        assertThat(chatMessageService.responseMetadata(chatId, turnStarted)).isEqualTo(responseMetadata);
    }

    /**
     * A turn no chat model answered leaves no row to read, and must come back as "nothing reported"
     * rather than throwing on the way to a {@code done} frame the client is waiting for.
     */
    @Test
    void responseMetadataIsNullWhenNoAssistantRowMatches() {
        ZonedDateTime turnStarted = ZonedDateTime.now();

        when(chatMessageRepository
                .findFirstByChatIdAndMessageTypeAndTimestampGreaterThanEqualOrderByTimestampDesc(
                        chatId, MessageType.ASSISTANT, turnStarted))
                .thenReturn(Optional.empty());

        assertThat(chatMessageService.responseMetadata(chatId, turnStarted)).isNull();
    }

    /**
     * The per-call breakdown counterpart to {@link #responseMetadataReadsBackWhatTheTurnRecorded}:
     * the {@code done} frame needs this read back explicitly too, or it stays null on that frame even
     * though the persisted row carries it.
     */
    @Test
    void responseMetadataCallsReadsBackWhatTheTurnRecorded() {
        ChatMessage assistantMessage = chatMessage(MessageType.ASSISTANT, "the answer");
        ZonedDateTime turnStarted = ZonedDateTime.now();

        List<ModelCallMetadata> calls = List.of(
                new ModelCallMetadata("qwen3-8b", "chatcmpl-1", null, "stop", 1042, 259, 1301, null, null, null, null,
                        null, null, null, null, null, null, null, null, null));

        assistantMessage.setResponseMetadataCalls(calls);

        when(chatMessageRepository
                .findFirstByChatIdAndMessageTypeAndTimestampGreaterThanEqualOrderByTimestampDesc(
                        chatId, MessageType.ASSISTANT, turnStarted))
                .thenReturn(Optional.of(assistantMessage));

        assertThat(chatMessageService.responseMetadataCalls(chatId, turnStarted)).isEqualTo(calls);
    }

    @Test
    void responseMetadataCallsIsNullWhenNoAssistantRowMatches() {
        ZonedDateTime turnStarted = ZonedDateTime.now();

        when(chatMessageRepository
                .findFirstByChatIdAndMessageTypeAndTimestampGreaterThanEqualOrderByTimestampDesc(
                        chatId, MessageType.ASSISTANT, turnStarted))
                .thenReturn(Optional.empty());

        assertThat(chatMessageService.responseMetadataCalls(chatId, turnStarted)).isNull();
    }

    /**
     * The cascade a deleted message takes with it: its own attachments and the images generated on
     * its turn, mirroring {@link ChatService#delete(UUID)}'s whole-chat cascade at message scope.
     */
    @Test
    void deleteAlsoClearsAttachmentsAndGeneratedImages() {
        ChatMessage message = chatMessage(MessageType.ASSISTANT, "the answer");

        when(chatMessageRepository.findById(message.getId())).thenReturn(Optional.of(message));

        chatMessageService.delete(chatId, message.getId());

        verify(chatService).requireOwned(chatId);
        verify(chatAttachmentService).deleteForChatMessage(message.getId());
        verify(generatedImageService).deleteForChatMessage(message.getId());
        verify(chatMessageRepository).delete(message);
    }

    /**
     * The owner comes from the ownership check, never from anything the caller sent, so the forget
     * cannot be pointed at another user's memories.
     */
    @Test
    void deleteForgetsWhatTheMessageAloneEvidencedForTheOwner() {
        UUID ownerId = UUID.randomUUID();
        ChatMessage message = chatMessage(MessageType.USER, "I moved to Leeds");

        when(chatService.requireOwned(chatId)).thenReturn(ownerId);
        when(chatMessageRepository.findById(message.getId())).thenReturn(Optional.of(message));

        chatMessageService.delete(chatId, message.getId());

        verify(userMemoryService).forgetChatMessage(chatId, message.getId(), ownerId);
    }

    @Test
    void deleteForgetsNothingForAMessageFromAnotherChat() {
        ChatMessage messageFromAnotherChat = chatMessage(MessageType.USER, "not this chat's question");
        messageFromAnotherChat.setChatId(UUID.randomUUID());

        when(chatMessageRepository.findById(messageFromAnotherChat.getId())).thenReturn(Optional.of(messageFromAnotherChat));

        assertThatThrownBy(() -> chatMessageService.delete(chatId, messageFromAnotherChat.getId()))
                .isInstanceOf(ResponseStatusException.class);

        verify(userMemoryService, never()).forgetChatMessage(any(), any(), any());
    }

    /**
     * Without this, any authenticated caller who knew or guessed another user's {@code chatId}
     * could delete that chat's messages.
     */
    @Test
    void deletePropagatesNotFoundForAChatTheCallerDoesNotOwn() {
        UUID messageId = UUID.randomUUID();

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(chatService).requireOwned(chatId);

        assertThatThrownBy(() -> chatMessageService.delete(chatId, messageId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(chatMessageRepository, never()).delete(any(ChatMessage.class));
    }

    /**
     * A message id from another chat must be indistinguishable from one that does not exist —
     * ownership of {@code chatId} alone is not enough to trust {@code messageId}.
     */
    @Test
    void deleteReportsAMessageFromAnotherChatAsNotFound() {
        ChatMessage messageFromAnotherChat = chatMessage(MessageType.ASSISTANT, "not this chat's answer");
        messageFromAnotherChat.setChatId(UUID.randomUUID());

        when(chatMessageRepository.findById(messageFromAnotherChat.getId()))
                .thenReturn(Optional.of(messageFromAnotherChat));

        assertThatThrownBy(() -> chatMessageService.delete(chatId, messageFromAnotherChat.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(chatMessageRepository, never()).delete(any(ChatMessage.class));
    }

    @Test
    void deleteReportsAMissingMessageAsNotFound() {
        UUID messageId = UUID.randomUUID();

        when(chatMessageRepository.findById(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatMessageService.delete(chatId, messageId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * An image used only as a reference has no description to replay. Without a line naming it, a
     * later turn would reach a model with no idea a photo was ever sent.
     */
    @Test
    void findByChatIdNamesAnImageThatWasNeverDescribed() {
        ChatMessage withImage = chatMessage(MessageType.USER, "make the sky purple");
        UUID attachmentId = UUID.randomUUID();

        when(chatMessageRepository.findByChatId(chatId)).thenReturn(List.of(
                withImage,
                chatMessage(MessageType.ASSISTANT, "here it is")));
        when(chatAttachmentService.undescribedImages(chatId)).thenReturn(List.of(new ChatAttachmentSummary(
                attachmentId, withImage.getId(), "beach.png", null, "image/png", 10L, false, null, false, null)));

        List<Message> messages = chatMessageService.findByChatId(chatId);

        assertThat(messages).hasSize(3);
        assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(messages.getFirst().getText())
                .contains("beach.png")
                .contains("never described");
        assertThat(messages.get(1).getText()).isEqualTo("make the sky purple");
    }
}
