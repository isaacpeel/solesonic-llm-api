package com.solesonic.service.rag;

import com.solesonic.model.VectorSearch;
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
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class VectorStoreService {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private static final int RETRIEVAL_TOP_K = 15;
    private static final int RERANK_TOP_N = 5;
    private static final int EXPANSION_QUERIES = 3;

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
                .build();

        this.taskChatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(taskOptions)
                .build();
    }

    public Advisor retrievalAugmentationAdvisor(UUID userId) {
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

        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(rewriteQueryTransformer, truncatingTransformer)
                .queryExpander(multiQueryExpander)
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(similarityThreshold)
                        .topK(RETRIEVAL_TOP_K)
                        .vectorStore(vectorStore)
                        .build())
                .documentPostProcessors(documentReranker)
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .build();
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
}
