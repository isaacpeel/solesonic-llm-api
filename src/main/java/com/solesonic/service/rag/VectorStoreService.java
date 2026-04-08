package com.solesonic.service.rag;

import com.solesonic.model.VectorSearch;
import com.solesonic.model.training.VectorDocument;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.ollama.VectorStoreRepository;
import com.solesonic.service.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
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

    private final VectorStore vectorStore;
    private final VectorStoreRepository vectorStoreRepository;
    private final UserPreferencesService userPreferencesService;

    @Value("${spring.ai.similarity-threshold}")
    private Double defaultSimilarityThreshold;

    public VectorStoreService(VectorStore vectorStore,
                              VectorStoreRepository vectorStoreRepository,
                              UserPreferencesService userPreferencesService) {
        this.vectorStore = vectorStore;
        this.vectorStoreRepository = vectorStoreRepository;
        this.userPreferencesService = userPreferencesService;
    }

    public Advisor retrievalAugmentationAdvisor(UUID userId) {
        UserPreferences userPreferences = userPreferencesService.get(userId);

        Double similarityThreshold = Optional.ofNullable(userPreferences.getSimilarityThreshold())
                .orElse(defaultSimilarityThreshold);

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(similarityThreshold)
                        .vectorStore(vectorStore)
                        .build())
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
