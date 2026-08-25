package com.solesonic.service.rag;

import com.solesonic.model.VectorSearch;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.model.training.VectorDocument;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.ollama.VectorStoreRepository;
import com.solesonic.service.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.model.rag.RetrievalMetadata.CHAT_ID;
import static com.solesonic.model.rag.RetrievalMetadata.SCOPE;
import static com.solesonic.model.rag.RetrievalMetadata.USER_ID;

@Service
public class VectorStoreService {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private static final int RETRIEVAL_TOP_K = 15;
    private static final int RERANK_TOP_N = 5;
    private static final int EXPANSION_QUERIES = 3;
    private static final String TASK_MODEL_KEEP_ALIVE = "30m";

    private final VectorStore vectorStore;
    private final VectorStoreRepository vectorStoreRepository;
    private final UserPreferencesService userPreferencesService;
    private final OllamaChatModel taskChatModel;

    @Value("${spring.ai.similarity-threshold}")
    private Double defaultSimilarityThreshold;

    @Value("${solesonic.llm.embedding.max-query-chars}")
    private int maxQueryChars;

    public VectorStoreService(VectorStore vectorStore,
                              VectorStoreRepository vectorStoreRepository,
                              UserPreferencesService userPreferencesService,
                              OllamaApi ollamaApi,
                              @Value("${solesonic.llm.tool-call.model:qwen2.5:7b}") String taskModel) {
        this.vectorStore = vectorStore;
        this.vectorStoreRepository = vectorStoreRepository;
        this.userPreferencesService = userPreferencesService;

        OllamaChatOptions taskOptions = OllamaChatOptions.builder()
                .model(taskModel)
                .numCtx(8192)
                .keepAlive(TASK_MODEL_KEEP_ALIVE)
                .temperature(0.0)
                .disableThinking()
                .build();

        this.taskChatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(taskOptions)
                .build();
    }

    /**
     * Builds the per-request RAG advisor, retrieving at conversation, user and global scope in that
     * order of precedence.
     * <p>
     * {@code chatId} may be null for a call that belongs to no conversation, in which case the
     * conversation tier is simply absent rather than filtered to nothing.
     */
    public Advisor retrievalAugmentationAdvisor(UUID userId, UUID chatId) {
        UserPreferences userPreferences = userPreferencesService.get(userId);

        Double similarityThreshold = Optional.ofNullable(userPreferences.getSimilarityThreshold())
                .orElse(defaultSimilarityThreshold);

        QueryTransformer truncatingTransformer = query -> {
            String text = query.text();
            if (text.length() <= maxQueryChars) {
                return query;
            }
            log.warn("Query text truncated from {} to {} characters for embedding", text.length(), maxQueryChars);
            return query.mutate().text(text.substring(0, maxQueryChars)).build();
        };

        RewriteQueryTransformer rewriteQueryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(taskChatModel))
                .build();

        MultiQueryExpander multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(taskChatModel))
                .numberOfQueries(EXPANSION_QUERIES)
                .build();

        LlmDocumentReranker documentReranker =
                new LlmDocumentReranker(ChatClient.builder(taskChatModel).build(), RERANK_TOP_N);

        RetrievalLoggingPostProcessor retrievalLoggingPostProcessor = new RetrievalLoggingPostProcessor();

        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(rewriteQueryTransformer, truncatingTransformer)
                .queryExpander(multiQueryExpander)
                .documentRetriever(new ScopedDocumentRetriever(
                        vectorStore, similarityThreshold, RETRIEVAL_TOP_K, tiers(userId, chatId)))
                .documentPostProcessors(retrievalLoggingPostProcessor, documentReranker)
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .build();
    }

    /**
     * The scope tiers to search, most specific first.
     * <p>
     * Built with {@link FilterExpressionBuilder} against the metadata keys every ingestion path
     * stamps, which pgvector converts to a JSON path predicate over the {@code metadata} column.
     * The ids are compared as strings because that is how they are written — a UUID serialized into
     * JSON is a string, and a filter comparing against anything else matches nothing.
     */
    private List<ScopedDocumentRetriever.ScopedTier> tiers(UUID userId, UUID chatId) {
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();

        List<ScopedDocumentRetriever.ScopedTier> tiers = new ArrayList<>(3);

        if (chatId != null) {
            Filter.Expression chatFilter = filterExpressionBuilder.and(
                    filterExpressionBuilder.eq(SCOPE, RetrievalScope.CHAT.name()),
                    filterExpressionBuilder.eq(CHAT_ID, chatId.toString())).build();

            tiers.add(new ScopedDocumentRetriever.ScopedTier(RetrievalScope.CHAT, chatFilter));
        }

        Filter.Expression userFilter = filterExpressionBuilder.and(
                filterExpressionBuilder.eq(SCOPE, RetrievalScope.USER.name()),
                filterExpressionBuilder.eq(USER_ID, userId.toString())).build();

        tiers.add(new ScopedDocumentRetriever.ScopedTier(RetrievalScope.USER, userFilter));

        Filter.Expression globalFilter = filterExpressionBuilder
                .eq(SCOPE, RetrievalScope.GLOBAL.name())
                .build();

        tiers.add(new ScopedDocumentRetriever.ScopedTier(RetrievalScope.GLOBAL, globalFilter));

        return tiers;
    }

    public void save(List<Document> documents) {
        try {
            vectorStore.accept(documents);
        } catch (Exception e) {
            log.error("Error saving vector", e);
            throw new RuntimeException(e);
        }
    }

    public List<Document> findSimilarDocuments(VectorSearch vectorSearch) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(vectorSearch.query())
                .similarityThreshold(vectorSearch.similarityThreshold())
                .topK(vectorSearch.topK())
                .build();

        return vectorStore.similaritySearch(searchRequest);
    }

    public List<VectorDocument> findByTrainingDocumentId(UUID trainingDocumentId) {
        return vectorStoreRepository.findByTrainingDocumentId(trainingDocumentId.toString())
                .orElse(Collections.emptyList());
    }

    public void delete(List<VectorDocument> vectorDocuments) {
        vectorStoreRepository.deleteAll(vectorDocuments);
    }

    public void delete(UUID trainingDocumentId) {
        vectorStoreRepository.deleteById(trainingDocumentId);
    }

    /**
     * Discards every conversation-scoped chunk of one chat. Joins the caller's transaction so that
     * a conversation and the documents that were attached to it go together or not at all.
     */
    @Transactional
    public void deleteByChatId(UUID chatId) {
        int deleted = vectorStoreRepository.deleteByChatId(chatId.toString());

        if (deleted > 0) {
            log.info("Deleted {} vector store chunk(s) of chat {}", deleted, chatId);
        }
    }

    /**
     * Discards the chunks of one attachment, for a user removing a single document from a
     * conversation they are keeping.
     */
    @Transactional
    public void deleteByChatAttachmentId(UUID chatAttachmentId) {
        int deleted = vectorStoreRepository.deleteByChatAttachmentId(chatAttachmentId.toString());

        if (deleted > 0) {
            log.info("Deleted {} vector store chunk(s) of attachment {}", deleted, chatAttachmentId);
        }
    }
}
