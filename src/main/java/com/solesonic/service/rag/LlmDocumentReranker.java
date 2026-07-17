package com.solesonic.service.rag;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link DocumentPostProcessor} that re-ranks retrieved documents with a language model,
 * keeping only the most relevant passages for the query. Passages are previewed (not sent in
 * full) to keep the ranking prompt small, and any failure falls back to the original retrieval
 * order so retrieval is never blocked by the re-ranker.
 */
@NullMarked
public class LlmDocumentReranker implements DocumentPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(LlmDocumentReranker.class);

    private static final int PASSAGE_PREVIEW_CHARS = 500;
    private static final Pattern INDEX_PATTERN = Pattern.compile("\\d{1,6}");

    private static final String RERANK_PROMPT = """
            You are a search result re-ranker. Given a user query and a numbered list of passages,
            select the passages most relevant to answering the query, most relevant first.
            Return only the passage numbers as a JSON array of integers, for example [3, 0, 5].
            Include at most %s numbers and never invent numbers outside the provided range.

            Query:
            %s

            Passages:
            %s
            """;

    private final ChatClient chatClient;
    private final int topN;

    public LlmDocumentReranker(ChatClient chatClient, int topN) {
        this.chatClient = chatClient;
        this.topN = topN;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.size() <= topN) {
            return documents;
        }

        String passages = formatPassages(documents);
        String prompt = RERANK_PROMPT.formatted(topN, query.text(), passages);

        List<Integer> ranking = requestRanking(prompt);

        return applyRanking(documents, ranking);
    }

    private List<Integer> requestRanking(String prompt) {
        try {
            return parseIndices(chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content());
        } catch (Exception exception) {
            log.warn("Re-ranking failed, falling back to retrieval order: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<Integer> parseIndices(@Nullable String content) {
        if (content == null) {
            return List.of();
        }

        List<Integer> indices = new ArrayList<>();
        Matcher matcher = INDEX_PATTERN.matcher(content);

        while (matcher.find()) {
            indices.add(Integer.parseInt(matcher.group()));
        }

        return indices;
    }

    private List<Document> applyRanking(List<Document> documents, List<Integer> ranking) {
        Set<Integer> ordered = new LinkedHashSet<>();

        for (Integer index : ranking) {
            if (index >= 0 && index < documents.size()) {
                ordered.add(index);
            }

            if (ordered.size() >= topN) {
                break;
            }
        }

        for (int index = 0; index < documents.size() && ordered.size() < topN; index++) {
            ordered.add(index);
        }

        List<Document> reranked = new ArrayList<>(topN);

        for (Integer index : ordered) {
            reranked.add(documents.get(index));
        }

        return reranked;
    }

    private String formatPassages(List<Document> documents) {
        StringBuilder builder = new StringBuilder();

        for (int index = 0; index < documents.size(); index++) {
            builder.append("[").append(index).append("] ").append(preview(documents.get(index).getText())).append("\n\n");
        }

        return builder.toString();
    }

    private String preview(@Nullable String text) {
        if (text == null) {
            return "";
        }

        if (text.length() <= PASSAGE_PREVIEW_CHARS) {
            return text;
        }

        return text.substring(0, PASSAGE_PREVIEW_CHARS);
    }
}
