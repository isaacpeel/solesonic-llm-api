package com.solesonic.repository.ingestion;

import com.solesonic.model.ingestion.DocumentEntitlement;
import com.solesonic.model.rag.GrantKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DocumentEntitlementRepository extends JpaRepository<DocumentEntitlement, UUID> {

    List<DocumentEntitlement> findByIngestedDocumentId(UUID ingestedDocumentId);

    /**
     * One document's grants of one kind. The read behind both
     * {@code DocumentEntitlementService.principals} and {@code retrievalKeys}, which is why the
     * {@code (ingested_document_id)} index exists.
     */
    List<DocumentEntitlement> findByIngestedDocumentIdAndGrantKind(UUID ingestedDocumentId, GrantKind grantKind);

    /**
     * The same question for a whole page of documents, in one query.
     * <p>
     * Asking per row is what listing did before it was paginated, and it was already the expensive
     * half of rendering a list — {@code StatusHistoryRepository.findLatestStatuses} exists for the
     * identical reason. Every summary needs its principals, so a page of twenty would otherwise be
     * twenty round trips.
     */
    List<DocumentEntitlement> findByIngestedDocumentIdInAndGrantKind(Collection<UUID> ingestedDocumentIds,
                                                                     GrantKind grantKind);

    /**
     * Clears one kind of grant on one document, so replacing a set is a delete plus an insert inside
     * one transaction rather than a read-modify-write that a concurrent ingest could lose.
     * <p>
     * Only ever the kind being replaced: promoting a chat document to a user's library replaces its
     * {@code RETRIEVE} grants and must leave {@code MANAGE} exactly as it was, or the person who
     * uploaded it loses the document they just promoted.
     */
    void deleteByIngestedDocumentIdAndGrantKind(UUID ingestedDocumentId, GrantKind grantKind);
}
