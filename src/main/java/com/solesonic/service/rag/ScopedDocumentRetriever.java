package com.solesonic.service.rag;

import com.solesonic.model.rag.DocumentPrincipal;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Retrieves from one vector store for several principals in precedence order, most specific first.
 * <p>
 * Every principal's material shares a table and they are told apart entirely by the
 * {@code entitlements} metadata key, so a tier is nothing more than a metadata filter. Each tier
 * runs as its own similarity search because one search with a disjunctive filter would rank purely
 * by distance — a conversation's own attached document would then compete with the whole global
 * knowledge base on similarity alone, and lose whenever the global corpus happened to phrase
 * something closer to the question.
 * <p>
 * Precedence is a waterfall over a shared budget: the most specific tier takes what it can from
 * {@code topK}, the next takes what is left, and so on. That makes precedence mean two things at
 * once, both of which are wanted — a conversation's own chunk is preferentially <em>included</em>
 * when the budget is tight, and preferentially <em>ordered</em> first in what is handed downstream.
 * <p>
 * <strong>De-duplicated by document id.</strong> This was previously unnecessary and is now
 * required: a chunk used to carry exactly one scope, which made the tiers mutually exclusive by
 * construction, but {@code entitlements} is a list and a chunk granted to both a conversation and a
 * user matches two tiers. Without this, such a chunk would be returned twice, consuming two of the
 * {@code topK} budget and reaching the model as a duplicated passage. The first tier to match wins,
 * which is also the most specific one — so de-duplication preserves the precedence rather than
 * fighting it.
 */
@NullMarked
public class ScopedDocumentRetriever implements DocumentRetriever {
    private static final Logger log = LoggerFactory.getLogger(ScopedDocumentRetriever.class);

    private final VectorStore vectorStore;
    private final int topK;
    private final List<ScopedTier> scopedTiers;

    /**
     * One scope's filter and similarity threshold, plus the scope itself for logging.
     * <p>
     * The threshold lives per tier rather than shared across all of them: a CHAT-scope chunk is
     * already narrowed to this exact conversation by its filter, which is a much stronger relevance
     * signal than "somewhere in the global corpus," so it can tolerate a looser bar than USER or
     * GLOBAL, where the threshold is doing real precision work over a much larger pool.
     */
    public record ScopedTier(DocumentPrincipal principal, Filter.Expression filterExpression, Double similarityThreshold) {
    }

    public ScopedDocumentRetriever(VectorStore vectorStore,
                                   int topK,
                                   List<ScopedTier> scopedTiers) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.scopedTiers = List.copyOf(scopedTiers);
    }

    @Override
    public List<Document> retrieve(Query query) {
        List<Document> retrieved = new ArrayList<>(topK);
        Set<String> seen = new LinkedHashSet<>();

        for (ScopedTier scopedTier : scopedTiers) {
            int remaining = topK - retrieved.size();

            if (remaining <= 0) {
                log.info("Retrieval budget of {} spent before reaching {}", topK, scopedTier.principal().key());
                break;
            }

            List<Document> tierDocuments = VectorStoreDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .similarityThreshold(scopedTier.similarityThreshold())
                    .topK(remaining)
                    .filterExpression(scopedTier.filterExpression())
                    .build()
                    .retrieve(query);

            log.info("Retrieved {} document(s) for {}", tierDocuments.size(), scopedTier.principal().key());

            for (Document tierDocument : tierDocuments) {
                if (seen.add(tierDocument.getId())) {
                    retrieved.add(tierDocument);
                }
            }
        }

        return retrieved;
    }
}
