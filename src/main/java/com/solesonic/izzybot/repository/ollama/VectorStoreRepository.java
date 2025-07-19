package com.solesonic.izzybot.repository.ollama;

import com.solesonic.izzybot.model.training.VectorDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VectorStoreRepository extends JpaRepository<VectorDocument, UUID> {

    @Query(value = """
        SELECT *
                FROM public.vector_store WHERE vector_store.metadata->>'TRAINING_DOCUMENT_ID' = :trainingDocumentId
        """
        , nativeQuery = true)
    Optional<List<VectorDocument>> findByTrainingDocumentId(@Param("trainingDocumentId") String trainingDocumentId);
}
