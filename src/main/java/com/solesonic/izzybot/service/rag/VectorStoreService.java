package com.solesonic.izzybot.service.rag;

import com.solesonic.izzybot.model.VectorSearch;
import com.solesonic.izzybot.model.training.VectorDocument;
import com.solesonic.izzybot.repository.ollama.VectorStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class VectorStoreService {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final VectorStore vectorStore;
    private final VectorStoreRepository vectorStoreRepository;

    public VectorStoreService(VectorStore vectorStore,
                              VectorStoreRepository vectorStoreRepository) {
        this.vectorStore = vectorStore;
        this.vectorStoreRepository = vectorStoreRepository;
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
