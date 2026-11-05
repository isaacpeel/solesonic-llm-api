package com.solesonic.service.rag;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.ingestion.IngestedDocumentRepository;
import com.solesonic.repository.rag.VectorStoreRepository;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.solesonic.model.rag.RetrievalMetadata.ENTITLEMENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real advisor {@link VectorStoreService} builds, run through a {@link ChatClient} against stub
 * models, so the gating is proven end to end: a chat with no documents of its own retrieves, and
 * costs, exactly what it did before; one with documents gets the floor, compression and manifest
 * only as configured.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorStoreServiceRetrievalTest {

    private static final LlmApiKey LLM_API_KEY = new LlmApiKey("sk-user-0000000001");

    @Mock
    private VectorStore vectorStore;

    @Mock
    private VectorStoreRepository vectorStoreRepository;

    @Mock
    private UserPreferencesService userPreferencesService;

    @Mock
    private OpenAiChatModel taskChatModel;

    @Mock
    private IngestedDocumentRepository ingestedDocumentRepository;

    private VectorStoreService vectorStoreService;

    private final List<Prompt> seenByChatModel = new ArrayList<>();

    private UUID userId;
    private UUID chatId;

    @BeforeEach
    void beforeEach() {
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();

        vectorStoreService = new VectorStoreService(vectorStore, vectorStoreRepository, userPreferencesService,
                taskChatModel, ingestedDocumentRepository);

        ReflectionTestUtils.setField(vectorStoreService, "defaultChatSimilarityThreshold", 0.5);
        ReflectionTestUtils.setField(vectorStoreService, "defaultUserSimilarityThreshold", 0.65);
        ReflectionTestUtils.setField(vectorStoreService, "defaultGlobalSimilarityThreshold", 0.75);
        ReflectionTestUtils.setField(vectorStoreService, "maxQueryChars", 200);

        when(userPreferencesService.get(userId)).thenReturn(new UserPreferences());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(taskChatModel.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(taskChatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(taskChatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Does the warranty cover labor?")))));
    }

    private void configure(boolean compression, int floor) {
        ReflectionTestUtils.setField(vectorStoreService, "queryCompressionEnabled", compression);
        ReflectionTestUtils.setField(vectorStoreService, "chatFloorTopK", floor);
    }

    private void chatHasDocuments(String... fileNames) {
        List<IngestedDocument> documents = new ArrayList<>();

        for (String fileName : fileNames) {
            IngestedDocument ingestedDocument = new IngestedDocument();
            ingestedDocument.setId(UUID.randomUUID());
            ingestedDocument.setFileName(fileName);
            documents.add(ingestedDocument);
        }

        when(ingestedDocumentRepository.findAllChatDocumentsManagedBy(eq(userId.toString()), eq(chatId.toString()),
                eq(DocumentStatus.COMPLETED), any(Pageable.class))).thenReturn(new PageImpl<>(documents));
    }

    /**
     * Sends {@code message} after one earlier exchange, through the advisor under test.
     */
    private String askFollowUp(String message) {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seenByChatModel.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
        };

        Advisor advisor = vectorStoreService.retrievalAugmentationAdvisor(userId, chatId, LLM_API_KEY);

        return ChatClient.builder(chatModel).build().prompt()
                .messages(new UserMessage("Summarize the attached warranty."),
                        new AssistantMessage("It covers parts for two years."))
                .user(message)
                .advisors(advisor)
                .call()
                .content();
    }

    private List<SearchRequest> searches() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());
        return captor.getAllValues();
    }

    private String lastUserTextSeenByModel() {
        return seenByChatModel.getLast().getUserMessage().getText();
    }

    /**
     * A shared conversation is read by people other than the sender, so it reaches into neither the
     * sender's own documents nor a chat's: the global corpus alone.
     */
    @Test
    void theSharedAdvisorSearchesOnlyTheGlobalTier() {
        configure(true, 3);

        Advisor advisor = vectorStoreService.sharedRetrievalAugmentationAdvisor(chatId, LLM_API_KEY);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seenByChatModel.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
        };

        ChatClient.builder(chatModel).build().prompt()
                .user("What is our holiday policy?")
                .advisors(advisor)
                .call()
                .content();

        List<SearchRequest> searches = searches();

        assertThat(searches).hasSize(1);
        assertThat(searches.getFirst().getFilterExpression().toString())
                .contains(ENTITLEMENTS)
                .contains("global")
                .doesNotContain(userId.toString())
                .doesNotContain(chatId.toString());
        assertThat(searches.getFirst().getSimilarityThreshold()).isEqualTo(0.75);
        verify(userPreferencesService, never()).get(any());
        verify(ingestedDocumentRepository, never()).findAllChatDocumentsManagedBy(any(), any(), any(), any());
    }

    @Test
    void aChatWithoutDocumentsBehavesAsBeforeEvenWithEverythingEnabled() {
        configure(true, 3);
        chatHasDocuments();

        askFollowUp("Does it cover labor?");

        verify(taskChatModel, never()).call(any(Prompt.class));
        assertThat(searches()).hasSize(3);
        assertThat(searches()).allSatisfy(search -> assertThat(search.getQuery()).isEqualTo("Does it cover labor?"));
        assertThat(lastUserTextSeenByModel()).isEqualTo("Does it cover labor?");
    }

    @Test
    void aChatWithDocumentsAndEverythingOffOnlyGainsTheManifest() {
        configure(false, 0);
        chatHasDocuments("warranty.pdf");

        askFollowUp("Does it cover labor?");

        verify(taskChatModel, never()).call(any(Prompt.class));
        assertThat(searches()).hasSize(3);
        assertThat(lastUserTextSeenByModel())
                .startsWith("Does it cover labor?")
                .contains("\"warranty.pdf\"")
                .contains("No passage from them matched");
    }

    @Test
    void aChatWithDocumentsGetsTheFloorWhenConfigured() {
        configure(false, 3);
        chatHasDocuments("warranty.pdf");

        askFollowUp("Summarize this");

        List<SearchRequest> searches = searches();

        assertThat(searches).hasSize(4);
        assertThat(searches.get(1).getSimilarityThreshold()).isZero();
        assertThat(searches.get(1).getTopK()).isEqualTo(3);
    }

    @Test
    void aFollowUpInAChatWithDocumentsIsCompressedBeforeItIsEmbedded() {
        configure(true, 0);
        chatHasDocuments("warranty.pdf");

        askFollowUp("Does it cover labor?");

        verify(taskChatModel).call(any(Prompt.class));
        assertThat(searches()).allSatisfy(search ->
                assertThat(search.getQuery()).isEqualTo("Does the warranty cover labor?"));

        // The model still answers the question as the user asked it.
        assertThat(lastUserTextSeenByModel()).startsWith("Does it cover labor?");
    }

    @Test
    void aFailingTaskModelFallsBackToTheRawQuery() {
        configure(true, 0);
        chatHasDocuments("warranty.pdf");
        when(taskChatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("task model down"));

        String answer = askFollowUp("Does it cover labor?");

        assertThat(answer).isEqualTo("ok");
        assertThat(searches()).allSatisfy(search -> assertThat(search.getQuery()).isEqualTo("Does it cover labor?"));
    }

    /**
     * Listing the chat's documents is one more query on the turn's path; if it fails, the turn
     * retrieves exactly as it did before rather than failing.
     */
    @Test
    void aFailingDocumentLookupDegradesToNoDocuments() {
        configure(true, 3);
        when(ingestedDocumentRepository.findAllChatDocumentsManagedBy(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        String answer = askFollowUp("Does it cover labor?");

        assertThat(answer).isEqualTo("ok");
        verify(taskChatModel, never()).call(any(Prompt.class));
        assertThat(searches()).hasSize(3);
    }

    @Test
    void theEmbeddedQueryIsTruncatedToTheLimit() {
        configure(false, 0);
        chatHasDocuments();

        askFollowUp("x".repeat(500));

        assertThat(searches()).allSatisfy(search -> assertThat(search.getQuery()).hasSize(200));
    }

    /**
     * A conversation-less call has no chat tier and no documents to look up.
     */
    @Test
    void aCallWithoutAChatNeverLooksUpDocuments() {
        configure(true, 3);

        Advisor advisor = vectorStoreService.retrievalAugmentationAdvisor(userId, null, LLM_API_KEY);

        assertThat(advisor).isNotNull();
        verify(ingestedDocumentRepository, never()).findAllChatDocumentsManagedBy(any(), any(), any(), any());
    }
}
