package com.solesonic.service.rag;

import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keeps the model aware of the documents attached to the conversation on every turn, not just the
 * one they were sent on.
 * <p>
 * Without this the model hears about an attachment once, in the attachment note of the send turn,
 * and never again: retrieved passages are not stored in chat memory, and an empty retrieval with
 * {@code allowEmptyContext} leaves no trace that a document exists — so on a later turn it may deny
 * having one. Implemented here, at the advisor, so every route that retrieves gets it (the default
 * route and MCP prompt commands build their prompts differently; a system-prompt manifest would
 * miss the second).
 * <ul>
 *   <li>No documents of its own: exactly what {@link ContextualQueryAugmenter} did before.</li>
 *   <li>Passages retrieved: the context block, naming the conversation's documents, and told it may
 *       be unrelated — the chat floor can surface a passage below the similarity threshold, and the
 *       model must not cite one that does not bear on the question.</li>
 *   <li>Nothing retrieved: the question unchanged, plus a note naming the documents and saying no
 *       passage matched, so the model says that instead of denying the document exists.</li>
 * </ul>
 * Only the last user message sent to the model is rewritten. Chat memory has already stored the
 * original text by then: its advisor runs first.
 */
@NullMarked
public class ChatDocumentQueryAugmenter implements QueryAugmenter {

    static final PromptTemplate CONTEXT_TEMPLATE = new PromptTemplate("""
            Context information is below. It may come from the documents attached to this conversation \
            ({documents}) or from the user's knowledge base, and it may be unrelated to the query.

            ---------------------
            {context}
            ---------------------

            Use the context only where it is relevant to the query. Where it is relevant, answer from it and \
            do not contradict it. Where it is not, ignore it and answer the query normally.
            Avoid statements like "Based on the context..." or "The provided information...".

            Query: {query}

            Answer:
            """);

    static final PromptTemplate NOTHING_RETRIEVED_TEMPLATE = new PromptTemplate("""
            {query}

            (Note for the assistant: this conversation has these attached documents: {documents}. \
            No passage from them matched this message. If the message is about those documents, say \
            you could not find a relevant passage in them and ask the user to rephrase or be more \
            specific. Otherwise answer normally.)
            """);

    static final int MAX_NAME_LENGTH = 120;

    private final List<String> documentNames;
    private final QueryAugmenter withoutDocuments;

    /**
     * @param documentNames the conversation's own retrievable documents; empty for a conversation
     *                      with none, which keeps the previous behaviour exactly
     */
    public ChatDocumentQueryAugmenter(List<String> documentNames) {
        this.documentNames = List.copyOf(documentNames);
        this.withoutDocuments = ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)
                .build();
    }

    @Override
    public Query augment(Query query, List<Document> documents) {
        if (documentNames.isEmpty()) {
            return withoutDocuments.augment(query, documents);
        }

        String names = documentNames.stream()
                .map(name -> "\"" + displayName(name) + "\"")
                .collect(Collectors.joining(", "));

        if (documents.isEmpty()) {
            return query.mutate()
                    .text(NOTHING_RETRIEVED_TEMPLATE.render(Map.of("query", query.text(), "documents", names)))
                    .build();
        }

        String context = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining(System.lineSeparator()));

        return query.mutate()
                .text(CONTEXT_TEMPLATE.render(Map.of("query", query.text(), "context", context, "documents", names)))
                .build();
    }

    /**
     * A file name is the uploader's own text. It is rendered as a template value (never parsed), but
     * it still lands in instructions to the model, so quotes and control characters that could close
     * the quoted name or start a new line are dropped, and the length is capped.
     */
    static String displayName(String fileName) {
        String cleaned = fileName.replaceAll("[\\p{Cntrl}\"`]", " ").replaceAll("\\s+", " ").strip();

        return cleaned.length() <= MAX_NAME_LENGTH ? cleaned : cleaned.substring(0, MAX_NAME_LENGTH) + "...";
    }
}
