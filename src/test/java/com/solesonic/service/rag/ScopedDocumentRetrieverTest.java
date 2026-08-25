package com.solesonic.service.rag;

import com.solesonic.model.rag.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScopedDocumentRetrieverTest {

    private static final int TOP_K = 5;

    @Mock
    private VectorStore vectorStore;

    private final FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

    private final Query query = new Query("what does the contract say?");

    private static final double CHAT_THRESHOLD = 0.5;
    private static final double USER_THRESHOLD = 0.65;
    private static final double GLOBAL_THRESHOLD = 0.75;

    private List<ScopedDocumentRetriever.ScopedTier> tiers() {
        return List.of(
                new ScopedDocumentRetriever.ScopedTier(RetrievalScope.CHAT,
                        filterExpressionBuilder.eq("scope", "CHAT").build(), CHAT_THRESHOLD),
                new ScopedDocumentRetriever.ScopedTier(RetrievalScope.USER,
                        filterExpressionBuilder.eq("scope", "USER").build(), USER_THRESHOLD),
                new ScopedDocumentRetriever.ScopedTier(RetrievalScope.GLOBAL,
                        filterExpressionBuilder.eq("scope", "GLOBAL").build(), GLOBAL_THRESHOLD));
    }

    private ScopedDocumentRetriever retriever() {
        return new ScopedDocumentRetriever(vectorStore, TOP_K, tiers());
    }

    private List<SearchRequest> capturedSearches() {
        ArgumentCaptor<SearchRequest> searchCaptor = ArgumentCaptor.forClass(SearchRequest.class);

        verify(vectorStore, org.mockito.Mockito.atLeastOnce()).similaritySearch(searchCaptor.capture());

        return searchCaptor.getAllValues();
    }

    private static String scopeOf(SearchRequest searchRequest) {
        Filter.Expression filterExpression = searchRequest.getFilterExpression();

        assertThat(filterExpression).isNotNull();

        Filter.Operand right = Objects.requireNonNull(filterExpression.right());

        assertThat(right).isInstanceOf(Filter.Value.class);

        return String.valueOf(((Filter.Value) right).value());
    }

    /**
     * The order the tiers are searched in is the order their results are handed downstream, which is
     * half of what precedence means here.
     */
    @Test
    void searchesConversationThenUserThenGlobal() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retriever().retrieve(query);

        assertThat(capturedSearches())
                .extracting(ScopedDocumentRetrieverTest::scopeOf)
                .containsExactly("CHAT", "USER", "GLOBAL");
    }

    /**
     * The other half: a conversation's own documents are preferentially <em>included</em>, not just
     * ordered first. A tier that fills the budget leaves nothing for the broader ones, so a chat
     * document cannot be crowded out by a global corpus that happens to phrase things closer to the
     * question.
     */
    @Test
    void stopsSearchingOnceTheBudgetIsSpent() {
        List<Document> full = List.of(
                new Document("one"), new Document("two"), new Document("three"),
                new Document("four"), new Document("five"));

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(full);

        List<Document> retrieved = retriever().retrieve(query);

        assertThat(retrieved).hasSize(TOP_K);

        //Only the conversation tier ran; the budget was gone before the other two.
        assertThat(capturedSearches())
                .extracting(ScopedDocumentRetrieverTest::scopeOf)
                .containsExactly("CHAT");
    }

    /**
     * A partly-filled tier hands the remainder on rather than letting the next tier search for the
     * whole budget again, which would return more documents than were asked for.
     */
    @Test
    void passesTheRemainingBudgetToTheNextTier() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("chat one"), new Document("chat two")))
                .thenReturn(List.of(new Document("user one")))
                .thenReturn(List.of(new Document("global one")));

        List<Document> retrieved = retriever().retrieve(query);

        assertThat(retrieved).hasSize(4);

        assertThat(capturedSearches())
                .extracting(SearchRequest::getTopK)
                .containsExactly(TOP_K, TOP_K - 2, TOP_K - 3);
    }

    /**
     * Each tier applies its own threshold rather than one shared across all three — a CHAT-scope
     * chunk is already narrowed to this exact conversation, so it can tolerate a looser bar than
     * USER or GLOBAL, where the threshold is doing real precision work over a much larger pool.
     */
    @Test
    void appliesEachTiersOwnSimilarityThreshold() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retriever().retrieve(query);

        assertThat(capturedSearches())
                .extracting(SearchRequest::getSimilarityThreshold)
                .containsExactly(CHAT_THRESHOLD, USER_THRESHOLD, GLOBAL_THRESHOLD);
    }

    @Test
    void searchesNothingWithoutTiers() {
        ScopedDocumentRetriever retriever = new ScopedDocumentRetriever(vectorStore, TOP_K, List.of());

        assertThat(retriever.retrieve(query)).isEmpty();

        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }
}
