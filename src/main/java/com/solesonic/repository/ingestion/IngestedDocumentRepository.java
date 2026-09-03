package com.solesonic.repository.ingestion;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.rag.PrincipalType;
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

/**
 * Ingested documents, found by who is entitled to them rather than by a scope column.
 * <p>
 * Every listing and every scoped fetch joins {@code document_entitlement}, so ownership is a
 * {@code where} clause and there is no path on which a caller can forget to apply it — the stance
 * {@code ChatAttachmentRepository.findByIdAndUserId} set, kept. A document belonging to another
 * principal is simply not found, which is why the collections answer {@code 404} rather than
 * {@code 403}.
 * <p>
 * There is no {@code SUMMARY_PROJECTION} any more. It existed only to keep {@code fileData} off a
 * listing page, and the bytes now live on {@code ingested_document_content} — so listings select
 * whole entities, which is what lets them join another table at all. That projection being
 * JPQL-only, and JPQL being unable to index into the json metadata column, is the entire reason the
 * {@code CHAT} listing once needed a {@code chat_id} column of its own.
 */
public interface IngestedDocumentRepository extends JpaRepository<IngestedDocument, UUID> {

    /**
     * One principal's documents, newest first (§6.1).
     * <p>
     * Ordered here rather than by a caller-supplied sort, for the reason
     * {@code ChatGroupController.chats} documents: an ordering appended to the query is either an
     * unknown property or a perturbation that paging cannot rely on.
     * <p>
     * The unique constraint on {@code document_entitlement} guarantees at most one matching grant
     * row per document, so this join cannot fan a document out into several rows.
     */
    @Query(value = """
            select ingestedDocument
              from IngestedDocument ingestedDocument
              join DocumentEntitlement entitlement
                on entitlement.ingestedDocumentId = ingestedDocument.id
             where entitlement.principalType = :principalType
               and entitlement.principalId = :principalId
               and entitlement.grantKind = com.solesonic.model.rag.GrantKind.RETRIEVE
             order by ingestedDocument.created desc
            """,
            countQuery = """
                    select count(ingestedDocument)
                      from IngestedDocument ingestedDocument
                      join DocumentEntitlement entitlement
                        on entitlement.ingestedDocumentId = ingestedDocument.id
                     where entitlement.principalType = :principalType
                       and entitlement.principalId = :principalId
                       and entitlement.grantKind = com.solesonic.model.rag.GrantKind.RETRIEVE
                    """)
    Page<IngestedDocument> findAllRetrievableBy(@Param("principalType") PrincipalType principalType,
                                                @Param("principalId") String principalId,
                                                Pageable pageable);

    /**
     * One document, if this principal may retrieve it. The scoped fetch every single-document
     * operation on the {@code GLOBAL} and {@code USER} collections opens with.
     */
    @Query("""
            select ingestedDocument
              from IngestedDocument ingestedDocument
              join DocumentEntitlement entitlement
                on entitlement.ingestedDocumentId = ingestedDocument.id
             where entitlement.principalType = :principalType
               and entitlement.principalId = :principalId
               and entitlement.grantKind = com.solesonic.model.rag.GrantKind.RETRIEVE
               and ingestedDocument.id = :id
            """)
    Optional<IngestedDocument> findRetrievableBy(@Param("id") UUID id,
                                                 @Param("principalType") PrincipalType principalType,
                                                 @Param("principalId") String principalId);

    /**
     * One document, if this principal <em>manages</em> it — the fetch the chat document suite and
     * every promote operation open with.
     * <p>
     * Distinct from {@link #findRetrievableBy} on purpose. A chat document is retrievable by the
     * conversation but managed by the person who uploaded it, so asking the retrieve question would
     * make a user's own chat document invisible to the endpoints that manage it.
     */
    @Query("""
            select ingestedDocument
              from IngestedDocument ingestedDocument
              join DocumentEntitlement entitlement
                on entitlement.ingestedDocumentId = ingestedDocument.id
             where entitlement.principalType = :principalType
               and entitlement.principalId = :principalId
               and entitlement.grantKind = com.solesonic.model.rag.GrantKind.MANAGE
               and ingestedDocument.id = :id
            """)
    Optional<IngestedDocument> findManageableBy(@Param("id") UUID id,
                                                @Param("principalType") PrincipalType principalType,
                                                @Param("principalId") String principalId);

    /**
     * Every document this user manages that is retrievable in some conversation (§6.2) — "everything
     * I have ever uploaded to any chat", which had no expressible query before and had to be
     * reconstructed by joining through {@code chat}.
     * <p>
     * The chat grant is an {@code exists} rather than a second join, so a document retrievable in two
     * conversations appears once rather than twice.
     * <p>
     * <strong>The {@code MANAGE} join is the authorization.</strong> There is no path on which it can
     * be skipped, and asking about a conversation belonging to someone else returns zero rows rather
     * than a {@code 403} — matching how {@code ChatService.get} answers.
     * <p>
     * {@code documentStatus} filters on the <em>latest</em> status, picked the same way
     * {@code StatusHistoryRepository.findQueued} picks it. It exists because a {@code FAILED} chat
     * document is invisible in every other surface: nothing lists it, and the user is never told
     * their attachment did not index.
     *
     * @param chatId         null lists every conversation; supplied narrows to one
     * @param documentStatus null lists every status
     */
    @Query(value = """
            select ingestedDocument
              from IngestedDocument ingestedDocument
              join DocumentEntitlement manageGrant
                on manageGrant.ingestedDocumentId = ingestedDocument.id
             where manageGrant.principalType = com.solesonic.model.rag.PrincipalType.USER
               and manageGrant.principalId = :userId
               and manageGrant.grantKind = com.solesonic.model.rag.GrantKind.MANAGE
               and exists (
                     select 1
                       from DocumentEntitlement chatGrant
                      where chatGrant.ingestedDocumentId = ingestedDocument.id
                        and chatGrant.principalType = com.solesonic.model.rag.PrincipalType.CHAT
                        and chatGrant.grantKind = com.solesonic.model.rag.GrantKind.RETRIEVE
                        and (:chatId is null or chatGrant.principalId = :chatId))
               and (:documentStatus is null or exists (
                     select 1
                       from StatusHistory statusHistory
                      where statusHistory.documentId = ingestedDocument.id
                        and statusHistory.documentStatus = :documentStatus
                        and not exists (
                              select 1
                                from StatusHistory later
                               where later.documentId = statusHistory.documentId
                                 and later.timestamp > statusHistory.timestamp)))
             order by ingestedDocument.created desc
            """,
            countQuery = """
                    select count(ingestedDocument)
                      from IngestedDocument ingestedDocument
                      join DocumentEntitlement manageGrant
                        on manageGrant.ingestedDocumentId = ingestedDocument.id
                     where manageGrant.principalType = com.solesonic.model.rag.PrincipalType.USER
                       and manageGrant.principalId = :userId
                       and manageGrant.grantKind = com.solesonic.model.rag.GrantKind.MANAGE
                       and exists (
                             select 1
                               from DocumentEntitlement chatGrant
                              where chatGrant.ingestedDocumentId = ingestedDocument.id
                                and chatGrant.principalType = com.solesonic.model.rag.PrincipalType.CHAT
                                and chatGrant.grantKind = com.solesonic.model.rag.GrantKind.RETRIEVE
                                and (:chatId is null or chatGrant.principalId = :chatId))
               and (:documentStatus is null or exists (
                     select 1
                       from StatusHistory statusHistory
                      where statusHistory.documentId = ingestedDocument.id
                        and statusHistory.documentStatus = :documentStatus
                        and not exists (
                              select 1
                                from StatusHistory later
                               where later.documentId = statusHistory.documentId
                                 and later.timestamp > statusHistory.timestamp)))
                    """)
    Page<IngestedDocument> findAllChatDocumentsManagedBy(@Param("userId") String userId,
                                                         @Param("chatId") String chatId,
                                                         @Param("documentStatus") DocumentStatus documentStatus,
                                                         Pageable pageable);

    /**
     * De-duplication lookup for uploads, restricted to the shared corpus. Two users uploading
     * {@code notes.pdf} to their own libraries are two documents, and matching one against the other
     * would hand the second uploader the first one's — so the restriction is in the query, not in a
     * check above it.
     */
    @Query("""
            select ingestedDocument
              from IngestedDocument ingestedDocument
              join DocumentEntitlement entitlement
                on entitlement.ingestedDocumentId = ingestedDocument.id
             where ingestedDocument.fileName = :fileName
               and entitlement.principalType = com.solesonic.model.rag.PrincipalType.GLOBAL
               and entitlement.grantKind = com.solesonic.model.rag.GrantKind.RETRIEVE
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
     * The row one chat attachment opened, found by the id stamped into its metadata.
     * <p>
     * Provenance, not entitlement. The {@code scope = 'CHAT'} guard these queries used to carry is
     * gone: it was only ever standing in for "is this really a chat document", and a promoted
     * document is still one that <em>came from</em> a chat. Deleting the attachment should still
     * reach it.
     * <p>
     * A list rather than an {@code Optional}: what the caller does with the answer is delete every
     * row in it, and one attachment having opened more than one row is something to clean up, not a
     * reason to fail.
     */
    @Query(value = """
        SELECT *
        FROM public.ingested_document td
        WHERE td.metadata->>'CHAT_ATTACHMENT_ID' = :CHAT_ATTACHMENT_ID
        """
        , nativeQuery = true)
    List<IngestedDocument> findByChatAttachmentId(@Param(CHAT_ATTACHMENT_ID) String chatAttachmentId);

    /**
     * Every row opened by any attachment of one conversation, for a chat being deleted. Provenance,
     * for the reason {@link #findByChatAttachmentId} gives.
     */
    @Query(value = """
        SELECT *
        FROM public.ingested_document td
        WHERE td.metadata->>'CHAT_ID' = :CHAT_ID
        """
        , nativeQuery = true)
    List<IngestedDocument> findByChatId(@Param(CHAT_ID) String chatId);
}
