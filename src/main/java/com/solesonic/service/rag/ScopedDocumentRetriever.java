package com.solesonic.service.rag;

import com.solesonic.model.rag.RetrievalScope;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;

/**
 * Retrieves from one vector store at several scopes in precedence order, most specific first.
 * <p>
 * The three scopes share a table and are told apart entirely by the {@code scope} metadata key, so a
 * tier is nothing more than a metadata filter. Each tier runs as its own similarity search because
 * one search with a disjunctive filter would rank purely by distance — a conversation's own attached
 * document would then compete with the whole global knowledge base on similarity alone, and lose
 * whenever the global corpus happened to phrase something closer to the question.
 * <p>
 * Precedence is a waterfall over a shared budget: the most specific tier takes what it can from
 * {@code topK}, the next takes what is left, and so on. That makes precedence mean two things at
 * once, both of which are wanted — a conversation-scoped chunk is preferentially <em>included</em>
 * when the budget is tight, and preferentially <em>ordered</em> first in what is handed downstream.
 * <p>
 * No de-duplication: a chunk carries exactly one scope, so the tiers are mutually exclusive by
 * construction and no document can match two of them.
 */
@NullMarked
public class ScopedDocumentRetriever implements DocumentRetriever {
    private static final Logger log = LoggerFactory.getLogger(ScopedDocumentRetriever.class);

    private final VectorStore vectorStore;
    private final int topK;
    private final List<ScopedTier> tiers;

    /**
     * One scope's filter and similarity threshold, plus the scope itself for logging.
     * <p>
     * The threshold lives per tier rather than shared across all of them: a CHAT-scope chunk is
     * already narrowed to this exact conversation by its filter, which is a much stronger relevance
     * signal than "somewhere in the global corpus," so it can tolerate a looser bar than USER or
     * GLOBAL, where the threshold is doing real precision work over a much larger pool.
     */
    public record ScopedTier(RetrievalScope scope, Filter.Expression filterExpression, Double similarityThreshold) {
    }

    public ScopedDocumentRetriever(VectorStore vectorStore,
                                   int topK,
                                   List<ScopedTier> tiers) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.tiers = List.copyOf(tiers);
    }

    @Override
    public List<Document> retrieve(Query query) {
        List<Document> retrieved = new ArrayList<>(topK);

        for (ScopedTier tier : tiers) {
            int remaining = topK - retrieved.size();

            if (remaining <= 0) {
                log.debug("Retrieval budget of {} spent before reaching scope {}", topK, tier.scope());

                break;
            }

            List<Document> tierDocuments = VectorStoreDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .similarityThreshold(tier.similarityThreshold())
                    .topK(remaining)
                    .filterExpression(tier.filterExpression())
                    .build()
                    .retrieve(query);

            log.debug("Retrieved {} document(s) at scope {}", tierDocuments.size(), tier.scope());

            retrieved.addAll(tierDocuments);
        }

        return retrieved;
    }
}
