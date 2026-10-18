package com.solesonic.service.memory;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.repository.chat.ChatMessageRepository;
import com.solesonic.repository.memory.MemoryEmbeddingRepository;
import com.solesonic.repository.memory.MemoryEmbeddingRepository.MemorySimilarity;
import com.solesonic.repository.memory.MemoryEmbeddingRepository.StaleMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryEmbeddingServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final String MODEL = "mxbai-embed-large";
    private static final int BATCH_SIZE = 10;
    private static final int TOP_K = 5;
    private static final double THRESHOLD = 0.5;
    private static final int QUERY_WINDOW_MESSAGES = 3;
    private static final int MAX_QUERY_CHARS = 1500;

    private static final float[] MESSAGE_VECTOR = {1f, 0f};
    private static final float[] CONVERSATION_VECTOR = {0f, 1f};

    @Mock
    private MemoryEmbeddingRepository memoryEmbeddingRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private EmbeddingModel embeddingModel;

    private MemoryEmbeddingService memoryEmbeddingService;

    @BeforeEach
    void setUp() {
        memoryEmbeddingService = new MemoryEmbeddingService(memoryEmbeddingRepository, chatMessageRepository, embeddingModel,
                MODEL, BATCH_SIZE, TOP_K, THRESHOLD, QUERY_WINDOW_MESSAGES, MAX_QUERY_CHARS);
    }

    /**
     * The message alone, and the message with the user's earlier messages newest first — so a change
     * of subject and a follow-up are both found.
     */
    @Test
    void searchesWithTheMessageAloneAndWithTheConversationSoFar() {
        when(chatMessageRepository.findByChatIdAndMessageTypeOrderByTimestampDesc(CHAT_ID, MessageType.USER, Limit.of(2)))
                .thenReturn(List.of(userMessage("What's the weather in Austin?"), userMessage("I'm planning a trip")));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(MESSAGE_VECTOR, CONVERSATION_VECTOR));

        memoryEmbeddingService.relevant(USER_ID, CHAT_ID, "What about tomorrow?");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queries = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embed(queries.capture());

        assertThat(queries.getValue()).containsExactly(
                "What about tomorrow?",
                "What about tomorrow?\nWhat's the weather in Austin?\nI'm planning a trip");
    }

    @Test
    void searchesWithTheMessageAloneOnTheFirstTurn() {
        when(chatMessageRepository.findByChatIdAndMessageTypeOrderByTimestampDesc(CHAT_ID, MessageType.USER, Limit.of(2)))
                .thenReturn(List.of());
        when(embeddingModel.embed(List.of("Hello"))).thenReturn(List.of(MESSAGE_VECTOR));

        memoryEmbeddingService.relevant(USER_ID, CHAT_ID, "Hello");

        verify(embeddingModel).embed(List.of("Hello"));
    }

    /**
     * Each memory keeps its better match of the two searches, and only matches at the threshold or
     * above count.
     */
    @Test
    void keepsTheBetterMatchOfEachMemoryAboveTheThreshold() {
        UUID both = UUID.randomUUID();
        UUID weak = UUID.randomUUID();

        when(chatMessageRepository.findByChatIdAndMessageTypeOrderByTimestampDesc(CHAT_ID, MessageType.USER, Limit.of(2)))
                .thenReturn(List.of(userMessage("earlier")));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(MESSAGE_VECTOR, CONVERSATION_VECTOR));
        when(memoryEmbeddingRepository.findNearest(USER_ID, MESSAGE_VECTOR, MODEL, TOP_K))
                .thenReturn(List.of(new MemorySimilarity(both, 0.6), new MemorySimilarity(weak, 0.4)));
        when(memoryEmbeddingRepository.findNearest(USER_ID, CONVERSATION_VECTOR, MODEL, TOP_K))
                .thenReturn(List.of(new MemorySimilarity(both, 0.8)));

        Map<UUID, Double> relevant = memoryEmbeddingService.relevant(USER_ID, CHAT_ID, "now");

        assertThat(relevant).containsOnly(Map.entry(both, 0.8));
    }

    @Test
    void findsNothingWithoutAnythingToSearchWith() {
        assertThat(memoryEmbeddingService.relevant(USER_ID, null, "   ")).isEmpty();

        verifyNoInteractions(embeddingModel, memoryEmbeddingRepository);
    }

    @Test
    void truncatesQueriesToTheEmbeddingLimit() {
        String longMessage = "x".repeat(MAX_QUERY_CHARS + 100);

        when(embeddingModel.embed(anyList())).thenReturn(List.of(MESSAGE_VECTOR));

        memoryEmbeddingService.relevant(USER_ID, null, longMessage);

        verify(embeddingModel).embed(List.of("x".repeat(MAX_QUERY_CHARS)));
    }

    @Test
    void embedsStaleMemoriesAgainstTheContentThatWasRead() {
        StaleMemory first = new StaleMemory(UUID.randomUUID(), USER_ID, "Lives in Austin");
        StaleMemory second = new StaleMemory(UUID.randomUUID(), USER_ID, "Is vegetarian");

        when(memoryEmbeddingRepository.findStale(USER_ID, MODEL, BATCH_SIZE)).thenReturn(List.of(first, second));
        when(embeddingModel.embed(List.of("Lives in Austin", "Is vegetarian"))).thenReturn(List.of(MESSAGE_VECTOR, CONVERSATION_VECTOR));
        when(memoryEmbeddingRepository.saveEmbedding(first.id(), USER_ID, "Lives in Austin", MESSAGE_VECTOR, MODEL)).thenReturn(true);
        when(memoryEmbeddingRepository.saveEmbedding(second.id(), USER_ID, "Is vegetarian", CONVERSATION_VECTOR, MODEL)).thenReturn(false);

        assertThat(memoryEmbeddingService.embedStale(USER_ID)).isEqualTo(1);
    }

    @Test
    void makesNoEmbeddingCallWhenNothingIsStale() {
        when(memoryEmbeddingRepository.findStale(MODEL, BATCH_SIZE)).thenReturn(List.of());

        assertThat(memoryEmbeddingService.embedStale()).isZero();
        verifyNoInteractions(embeddingModel);
    }

    /**
     * An embedding failure after commit is left for the task; it never reaches the caller that
     * changed the memory.
     */
    @Test
    void swallowsAFailureWhenEmbeddingAfterAChange() {
        when(memoryEmbeddingRepository.findStale(USER_ID, MODEL, BATCH_SIZE))
                .thenReturn(List.of(new StaleMemory(UUID.randomUUID(), USER_ID, "Lives in Austin")));
        when(embeddingModel.embed(anyList())).thenThrow(new IllegalStateException("embedding model unavailable"));

        memoryEmbeddingService.onMemoriesChanged(new MemoriesChangedEvent(USER_ID));

        verify(memoryEmbeddingRepository, never()).saveEmbedding(any(), any(), any(), any(), eq(MODEL));
    }

    private static ChatMessage userMessage(String text) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(CHAT_ID);
        chatMessage.setMessageType(MessageType.USER);
        chatMessage.setMessage(text);
        return chatMessage;
    }
}
