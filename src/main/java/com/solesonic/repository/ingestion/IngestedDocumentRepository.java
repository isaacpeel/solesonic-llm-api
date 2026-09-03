package com.solesonic.repository.ingestion;

import com.solesonic.model.ingestion.IngestedDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.model.ingestion.IngestedDocument.CONFLUENCE_PAGE_ID;
import static com.solesonic.model.ingestion.IngestedDocument.SOURCE_URI;
import static com.solesonic.model.ingestion.IngestedDocument.CHAT_ATTACHMENT_ID;
import static com.solesonic.model.ingestion.IngestedDocument.CHAT_ID;

public interface IngestedDocumentRepository extends JpaRepository<IngestedDocument, UUID> {

    String SUMMARY_PROJECTION = """
            new IngestedDocument(ingestedDocument.id, ingestedDocument.fileName,
                                 ingestedDocument.contentType, ingestedDocument.documentSource,
                                 ingestedDocument.scope, ingestedDocument.userId,
                                 ingestedDocument.chatId, ingestedDocument.metadata,
                                 ingestedDocument.created, ingestedDocument.updated)
            """;

    /**
     * The shared corpus, newest first.
     * <p>
     * Ordered here rather than by a caller-supplied sort, for the reason
     * {@code ChatGroupController.chats} documents: an ordering appended to the query is either an
     * unknown property or a perturbation that paging cannot rely on.
     */
    @Query(value = "select " + SUMMARY_PROJECTION + """
             from IngestedDocument ingestedDocument
            where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.GLOBAL
            order by ingestedDocument.created desc
            """,
            countQuery = """
                    select count(ingestedDocument)
                      from IngestedDocument ingestedDocument
                     where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.GLOBAL
                    """)
    Page<IngestedDocument> findAllGlobal(Pageable pageable);

    /**
     * The scope is part of the query rather than something the caller checks afterwards — the same
     * shape {@link #findByIdAndUserId} uses for ownership, and the reason neither has a
     * fetch-then-compare step anywhere above it. A {@code USER} document asked for by id through the
     * global collection is simply not found.
     * <p>
     * The whole row, not the {@link #SUMMARY_PROJECTION}: this is the fetch every single-document
     * operation starts from, mutations included, and a projected instance handed to {@code save}
     * would write its unloaded {@code fileData} back as null.
     */
    @Query("""
            select ingestedDocument
              from IngestedDocument ingestedDocument
             where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.GLOBAL
               and ingestedDocument.id = :id
            """)
    Optional<IngestedDocument> findGlobalById(UUID id);

    @Query(value = "select " + SUMMARY_PROJECTION + """
             from IngestedDocument ingestedDocument
            where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.USER
              and ingestedDocument.userId = :userId
            order by ingestedDocument.created desc
            """,
            countQuery = """
                    select count(ingestedDocument)
                      from IngestedDocument ingestedDocument
                     where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.USER
                       and ingestedDocument.userId = :userId
                    """)
    Page<IngestedDocument> findAllByUserId(UUID userId, Pageable pageable);

    /**
     * The query the {@code USER} collection's ownership rests on. Named and shaped after
     * {@code ChatAttachmentRepository.findByIdAndUserId} deliberately: the ownership check lives in
     * the {@code where} clause, so there is no path on which a caller can forget to apply it.
     * <p>
     * The whole row, for the same reason {@link #findGlobalById} loads one.
     */
    @Query("""
            select ingestedDocument
              from IngestedDocument ingestedDocument
             where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.USER
               and ingestedDocument.userId = :userId
               and ingestedDocument.id = :id
            """)
    Optional<IngestedDocument> findByIdAndUserId(UUID id, UUID userId);

    /**
     * One conversation's documents, both the ones attached to its messages and the ones uploaded to
     * it directly. Keyed on the {@code chatId} column rather than the {@code CHAT_ID} metadata key
     * the teardown queries below use, because this one has to project {@code fileData} away and a
     * native query cannot.
     */
    @Query(value = "select " + SUMMARY_PROJECTION + """
             from IngestedDocument ingestedDocument
            where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.CHAT
              and ingestedDocument.chatId = :chatId
            order by ingestedDocument.created desc
            """,
            countQuery = """
                    select count(ingestedDocument)
                      from IngestedDocument ingestedDocument
                     where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.CHAT
                       and ingestedDocument.chatId = :chatId
                    """)
    Page<IngestedDocument> findAllByChatId(UUID chatId, Pageable pageable);

    /**
     * The {@code CHAT} collection's scoped fetch, shaped after {@link #findByIdAndUserId}: the
     * conversation is in the {@code where} clause, so a document of another chat is absent rather
     * than fetched and then rejected. The whole row, for the reason {@link #findGlobalById} gives.
     */
    @Query("""
            select ingestedDocument
              from IngestedDocument ingestedDocument
             where ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.CHAT
               and ingestedDocument.chatId = :chatId
               and ingestedDocument.id = :id
            """)
    Optional<IngestedDocument> findByIdAndChatId(UUID id, UUID chatId);

    /**
     * De-duplication lookup for uploads, restricted to the shared corpus. Two users uploading
     * {@code notes.pdf} at {@code USER} scope are two documents, and matching one against the other
     * would hand the second uploader the first one's.
     */
    @Query("""
            select ingestedDocument
              from IngestedDocument ingestedDocument
             where ingestedDocument.fileName = :fileName
               and ingestedDocument.scope = com.solesonic.model.rag.RetrievalScope.GLOBAL
            """)
    Optional<IngestedDocument> findGlobalByFileName(String fileName);

    @Query(value = """
        SELECT *
        FROM public.ingested_document td
        WHERE td.metadata->>'CONFLUENCE_PAGE_ID' = :CONFLUENCE_PAGE_ID
        """
        , nativeQuery = true)
    Optional<List<IngestedDocument>> findByConfluenceId(@Param(CONFLUENCE_PAGE_ID) String externalId);

    @Query(value = """
        SELECT DISTINCT td.metadata->>'CONFLUENCE_PAGE_ID'
        FROM public.ingested_document td
        WHERE td.document_source = 'CONFLUENCE'
          AND td.metadata->>'CONFLUENCE_PAGE_ID' IS NOT NULL
        """
        , nativeQuery = true)
    List<String> findConfluencePageIds();

    @Query(value = """
        SELECT *
        FROM public.ingested_document td
        WHERE td.document_source = 'URI'
          AND td.metadata->>'SOURCE_URI' = :SOURCE_URI
        """
        , nativeQuery = true)
    Optional<List<IngestedDocument>> findBySourceUri(@Param(SOURCE_URI) String sourceUri);

    /**
     * The {@code CHAT} row one attachment opened, found by the id
     * {@code IngestedDocumentService.beginChatIngestion} stamped into its metadata. Keyed on the
     * metadata column rather than a column of its own, following {@link #findByConfluenceId} and
     * {@link #findBySourceUri}; the scope is in the {@code where} clause for the same reason it is
     * in {@link #findGlobalById}.
     * <p>
     * A list rather than an {@code Optional}: what the caller does with the answer is delete every
     * row in it, and one attachment having opened more than one row is something to clean up, not a
     * reason to fail.
     */
    @Query(value = """
        SELECT *
        FROM public.ingested_document td
        WHERE td.scope = 'CHAT'
          AND td.metadata->>'CHAT_ATTACHMENT_ID' = :CHAT_ATTACHMENT_ID
        """
        , nativeQuery = true)
    List<IngestedDocument> findByChatAttachmentId(@Param(CHAT_ATTACHMENT_ID) String chatAttachmentId);

    /**
     * Every {@code CHAT} row opened by any attachment of one conversation, for a chat being deleted.
     */
    @Query(value = """
        SELECT *
        FROM public.ingested_document td
        WHERE td.scope = 'CHAT'
          AND td.metadata->>'CHAT_ID' = :CHAT_ID
        """
        , nativeQuery = true)
    List<IngestedDocument> findByChatId(@Param(CHAT_ID) String chatId);
}
