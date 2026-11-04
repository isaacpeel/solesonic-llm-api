package com.solesonic.service.rag;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * Caps the query at what the embedding model accepts ({@code solesonic.llm.embedding.max-query-chars}).
 * <p>
 * Runs last in the chain, after {@link HistoryAwareQueryCompressor}: a compressed query can come
 * back longer than the message it was built from, and it is the result that gets embedded.
 */
@NullMarked
public class TruncatingQueryTransformer implements QueryTransformer {
    private static final Logger log = LoggerFactory.getLogger(TruncatingQueryTransformer.class);

    private final int maxQueryChars;

    public TruncatingQueryTransformer(int maxQueryChars) {
        this.maxQueryChars = maxQueryChars;
    }

    @Override
    public Query transform(Query query) {
        String text = query.text();

        if (text.length() <= maxQueryChars) {
            return query;
        }

        log.warn("Query text truncated from {} to {} characters for embedding", text.length(), maxQueryChars);

        return query.mutate().text(text.substring(0, maxQueryChars)).build();
    }
}
