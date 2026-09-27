package com.solesonic.repository.rag;

import com.solesonic.model.ingestion.VectorDocument;
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
                FROM public.vector_store WHERE vector_store.metadata->>'INGESTED_DOCUMENT_ID' = :ingestedDocumentId
        """
        , nativeQuery = true)
    Optional<List<VectorDocument>> findByIngestedDocumentId(@Param("ingestedDocumentId") String ingestedDocumentId);

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

    /**
     * Re-points every chunk of one document at a new audience, and drops the chat provenance that
     * would otherwise take it with the conversation it came from.
     * <p>
     * <strong>Not a re-queue.</strong> An audience change touches only filter keys, never the
     * vectors, so re-embedding would pay the whole ETL cost to arrive at identical embeddings — and
     * would leave the document unretrievable for as long as that took.
     * <p>
     * The two provenance keys are removed here rather than kept, which is the one place this model
     * deliberately gives up an audit trail: {@link #deleteByChatId} matches on {@code chatId}, so a
     * promoted document that kept it would lose its chunks the next time its origin conversation was
     * deleted — leaving a {@code COMPLETED} document in the user's library that retrieves nothing and
     * reports no error. Clearing the key is what retires that hazard.
     * <p>
     * {@code metadata} is {@code json}, so it is cast to {@code jsonb} to use {@code -} and
     * {@code ||}, and cast back on the way in.
     *
     * @param entitlements the new array, as a JSON string — e.g. {@code ["user:abc"]}
     */
    @Modifying
    @Query(value = """
        UPDATE public.vector_store
           SET metadata = ((metadata::jsonb - 'chatId' - 'chatAttachmentId')
                            || jsonb_build_object('entitlements', CAST(:entitlements AS jsonb)))::json
         WHERE vector_store.metadata->>'INGESTED_DOCUMENT_ID' = :ingestedDocumentId
        """
        , nativeQuery = true)
    int promoteChunks(@Param("ingestedDocumentId") String ingestedDocumentId,
                      @Param("entitlements") String entitlements);
}
