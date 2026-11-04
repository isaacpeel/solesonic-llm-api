package com.solesonic.service.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HistoryAwareQueryCompressor} and {@link TruncatingQueryTransformer}, alone and in the order
 * {@code VectorStoreService} chains them.
 */
class QueryTransformersTest {

    private static final List<Message> FOLLOW_UP_HISTORY = List.of(
            new SystemMessage("You are Izzy."),
            new UserMessage("Summarize the attached warranty."),
            new AssistantMessage("It covers parts for two years."),
            new UserMessage("Does it cover labor?"));

    private static final List<Message> FIRST_TURN_HISTORY = List.of(
            new SystemMessage("You are Izzy."),
            new UserMessage("Does the warranty cover labor?"));

    private final AtomicInteger compressorCalls = new AtomicInteger();

    private QueryTransformer compressor(String result) {
        return query -> {
            compressorCalls.incrementAndGet();
            return query.mutate().text(result).build();
        };
    }

    private static Query query(String text, List<Message> history) {
        return Query.builder().text(text).history(history).build();
    }

    @Test
    void compressesAFollowUpInAChatWithDocuments() {
        HistoryAwareQueryCompressor transformer = new HistoryAwareQueryCompressor(true, true,
                () -> compressor("Does the warranty cover labor?"));

        Query transformed = transformer.transform(query("Does it cover labor?", FOLLOW_UP_HISTORY));

        assertThat(transformed.text()).isEqualTo("Does the warranty cover labor?");
    }

    /**
     * A first message has nothing to fold in; the system message and the message itself are always
     * in the history, so their presence must not trigger a model call.
     */
    @Test
    void isAnIdentityOnTheFirstTurn() {
        HistoryAwareQueryCompressor transformer = new HistoryAwareQueryCompressor(true, true, () -> compressor("x"));

        Query original = query("Does the warranty cover labor?", FIRST_TURN_HISTORY);

        assertThat(transformer.transform(original)).isSameAs(original);
        assertThat(compressorCalls).hasValue(0);
    }

    @Test
    void isAnIdentityForAChatWithoutDocuments() {
        HistoryAwareQueryCompressor transformer = new HistoryAwareQueryCompressor(true, false, () -> compressor("x"));

        Query original = query("Does it cover labor?", FOLLOW_UP_HISTORY);

        assertThat(transformer.transform(original)).isSameAs(original);
        assertThat(compressorCalls).hasValue(0);
    }

    @Test
    void isAnIdentityWhenDisabled() {
        HistoryAwareQueryCompressor transformer = new HistoryAwareQueryCompressor(false, true, () -> compressor("x"));

        Query original = query("Does it cover labor?", FOLLOW_UP_HISTORY);

        assertThat(transformer.transform(original)).isSameAs(original);
        assertThat(compressorCalls).hasValue(0);
    }

    /**
     * Retrieval must never fail a turn.
     */
    @Test
    void fallsBackToTheRawQueryWhenCompressionFails() {
        HistoryAwareQueryCompressor transformer = new HistoryAwareQueryCompressor(true, true, () -> query -> {
            throw new IllegalStateException("task model unavailable");
        });

        Query original = query("Does it cover labor?", FOLLOW_UP_HISTORY);

        assertThat(transformer.transform(original)).isSameAs(original);
    }

    @Test
    void fallsBackToTheRawQueryWhenCompressionReturnsNothing() {
        HistoryAwareQueryCompressor transformer = new HistoryAwareQueryCompressor(true, true, () -> compressor("  "));

        Query original = query("Does it cover labor?", FOLLOW_UP_HISTORY);

        assertThat(transformer.transform(original)).isSameAs(original);
    }

    @Test
    void truncatesToTheEmbeddingLimit() {
        Query transformed = new TruncatingQueryTransformer(5).transform(new Query("abcdefghij"));

        assertThat(transformed.text()).isEqualTo("abcde");
    }

    /**
     * Compression runs first and truncation last: a compressed query can be longer than the message
     * it came from, and the result is what gets embedded.
     */
    @Test
    void truncationAppliesToTheCompressedQuery() {
        List<QueryTransformer> chain = List.of(
                new HistoryAwareQueryCompressor(true, true, () -> compressor("a".repeat(50))),
                new TruncatingQueryTransformer(10));

        Query transformed = query("short?", FOLLOW_UP_HISTORY);

        for (QueryTransformer queryTransformer : chain) {
            transformed = queryTransformer.transform(transformed);
        }

        assertThat(transformed.text()).hasSize(10);
    }
}
