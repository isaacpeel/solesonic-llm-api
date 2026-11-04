package com.solesonic.service.rag;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.function.Supplier;

/**
 * Folds the conversation into a follow-up before it is embedded, so "what does it say about
 * refunds?" is searched as a question about the attached document rather than about "it".
 * <p>
 * Retrieval otherwise embeds the raw last message only, and a follow-up carries none of the words
 * that would match the document it is about. This delegates to Spring AI's
 * {@code CompressionQueryTransformer}, which exists for exactly this.
 * <p>
 * An identity unless all of these hold, so chats without documents pay no extra model call:
 * <ul>
 *   <li>the feature is enabled ({@code solesonic.llm.retrieval.query-compression.enabled});</li>
 *   <li>the conversation has retrievable documents of its own;</li>
 *   <li>there is an earlier turn to fold in. {@code Query.history()} is the whole prompt — system
 *       message and the current message included — so "non-empty" says nothing; a prior
 *       assistant reply is what makes a message a follow-up.</li>
 * </ul>
 * Retrieval must never fail a turn: any failure, or a blank result, falls back to the raw query.
 */
@NullMarked
public class HistoryAwareQueryCompressor implements QueryTransformer {
    private static final Logger log = LoggerFactory.getLogger(HistoryAwareQueryCompressor.class);

    private final boolean active;
    private final Supplier<QueryTransformer> compressor;

    /**
     * @param compressor built only when first needed, so an inactive one costs nothing
     */
    public HistoryAwareQueryCompressor(boolean enabled, boolean hasChatDocuments, Supplier<QueryTransformer> compressor) {
        this.active = enabled && hasChatDocuments;
        this.compressor = compressor;
    }

    @Override
    public Query transform(Query query) {
        if (!active || !isFollowUp(query)) {
            return query;
        }

        try {
            Query compressed = compressor.get().transform(query);

            if (compressed.text().isBlank()) {
                log.warn("Query compression returned nothing; retrieving with the original message");

                return query;
            }

            log.debug("Compressed a {}-character follow-up into a {}-character standalone query",
                    query.text().length(), compressed.text().length());

            return compressed;
        } catch (RuntimeException runtimeException) {
            log.warn("Query compression failed; retrieving with the original message: {}",
                    runtimeException.getMessage());

            return query;
        }
    }

    private static boolean isFollowUp(Query query) {
        return query.history().stream()
                .anyMatch(message -> message.getMessageType() == MessageType.ASSISTANT);
    }
}
