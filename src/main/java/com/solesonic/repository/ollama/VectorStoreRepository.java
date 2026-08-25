package com.solesonic.repository.ollama;

import com.solesonic.model.training.VectorDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Discards every chunk belonging to one conversation. There is no foreign key from the vector
     * store to {@code chat}, so a deleted conversation would otherwise keep answering questions
     * from the documents that were attached to it.
     * <p>
     * Matches on the metadata key rather than a column, which is where scope lives — see
     * {@code RetrievalMetadata}.
     */
    @Modifying
    @Query(value = """
        DELETE FROM public.vector_store WHERE vector_store.metadata->>'chatId' = :chatId
        """
        , nativeQuery = true)
    int deleteByChatId(@Param("chatId") String chatId);

    /**
     * Discards the chunks of exactly one attachment, for the case where a user removes a single
     * document but keeps the conversation.
     */
    @Modifying
    @Query(value = """
        DELETE FROM public.vector_store WHERE vector_store.metadata->>'chatAttachmentId' = :chatAttachmentId
        """
        , nativeQuery = true)
    int deleteByChatAttachmentId(@Param("chatAttachmentId") String chatAttachmentId);
}
