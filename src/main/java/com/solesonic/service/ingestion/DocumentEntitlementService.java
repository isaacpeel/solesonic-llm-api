package com.solesonic.service.ingestion;

import com.solesonic.model.ingestion.DocumentEntitlement;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.GrantKind;
import com.solesonic.repository.ingestion.DocumentEntitlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who may retrieve and who may manage each ingested document.
 * <p>
 * <strong>This is the single derivation.</strong> {@link #retrievalKeys(UUID)} is the only thing in
 * the codebase that produces the entitlement keys stamped into chunk metadata, which is what keeps
 * the chunk side a materialized projection of these rows rather than a second source of truth.
 * {@code DocumentService} used to hand-assemble those keys from the document's metadata map while
 * the columns were hand-set at five separate call sites; nothing derived one from the other, so
 * nothing stopped them disagreeing — and {@code V3_25} exists because they did.
 * <p>
 * The rule that keeps it true: nothing outside this class assembles an entitlement key. If a caller
 * needs to know what a document's chunks say, it asks here.
 */
@Service
public class DocumentEntitlementService {
    private static final Logger log = LoggerFactory.getLogger(DocumentEntitlementService.class);

    private final DocumentEntitlementRepository documentEntitlementRepository;

    public DocumentEntitlementService(DocumentEntitlementRepository documentEntitlementRepository) {
        this.documentEntitlementRepository = documentEntitlementRepository;
    }

    /**
     * The grant every ingestion path writes: retrievable by one principal, managed by another.
     * <p>
     * One method rather than two calls because the two are never independently correct — a document
     * with no {@code MANAGE} grant belongs to nobody and can never be deleted through any endpoint,
     * and one with no {@code RETRIEVE} grant is invisible to the retrieval it was ingested for. A
     * row missing either is exactly the "row nothing can reach" that {@code V3_14} had to repair,
     * and making both a single call is what stops a new ingestion path writing half of it.
     */
    @Transactional
    public void grantOwnership(UUID documentId,
                               DocumentPrincipal retrievableBy,
                               DocumentPrincipal managedBy,
                               UUID grantedBy) {
        grant(documentId, GrantKind.RETRIEVE, List.of(retrievableBy), grantedBy);
        grant(documentId, GrantKind.MANAGE, List.of(managedBy), grantedBy);
    }

    @Transactional
    public void grant(UUID documentId,
                      GrantKind grantKind,
                      Collection<DocumentPrincipal> principals,
                      UUID grantedBy) {
        List<DocumentEntitlement> entitlements = distinct(principals).stream()
                .map(principal -> new DocumentEntitlement(documentId, principal, grantKind, grantedBy))
                .toList();

        if (entitlements.isEmpty()) {
            return;
        }

        log.debug("Granting {} on document {} to {} principal(s)", grantKind, documentId, entitlements.size());

        documentEntitlementRepository.saveAll(entitlements);
    }

    /**
     * Re-points a document at a different audience, leaving management alone.
     * <p>
     * This is the whole of what promoting a document is. Chat to user, chat to global, share with a
     * team later — all of them are this one operation with a different argument, which is what
     * replaced the column dance the old model would have needed and why there is no scope-specific
     * promote method per target.
     * <p>
     * Delete-then-insert inside one transaction, rather than reading the set and reconciling it: the
     * ingest pipeline may be touching the same document, and a read-modify-write is where a lost
     * update would come from.
     */
    @Transactional
    public void replaceRetrieveGrants(UUID documentId, Collection<DocumentPrincipal> principals, UUID grantedBy) {
        replaceGrants(documentId, GrantKind.RETRIEVE, principals, grantedBy);
    }

    /**
     * Used when a document changes hands rather than audience — promoting into the shared corpus,
     * where the only manager becomes the {@code rag-admin} role and the uploading admin keeps no
     * personal grant.
     */
    @Transactional
    public void replaceManageGrants(UUID documentId, Collection<DocumentPrincipal> principals, UUID grantedBy) {
        replaceGrants(documentId, GrantKind.MANAGE, principals, grantedBy);
    }

    /**
     * Everything granted this kind of access to this document.
     */
    public List<DocumentPrincipal> principals(UUID documentId, GrantKind grantKind) {
        return documentEntitlementRepository.findByIngestedDocumentIdAndGrantKind(documentId, grantKind).stream()
                .map(DocumentEntitlement::principal)
                .toList();
    }

    /**
     * What this document's chunks carry in their {@code entitlements} metadata array — the one
     * place that array is ever produced.
     * <p>
     * {@code RETRIEVE} only. Management is not a retrieval concept and a filter would never compare
     * against it, so projecting it outward would grow the chunk-side array with keys nothing reads.
     * <p>
     * A document mid-ingest whose grants have not been written yet returns empty, and empty is
     * deliberately <em>not</em> silently turned into "global": a chunk stamped with no entitlement
     * is invisible, which is a bug that shows up as a document that retrieves nothing, whereas a
     * chunk wrongly stamped global is a disclosure. Callers must not paper over the empty case.
     */
    public List<String> retrievalKeys(UUID documentId) {
        return principals(documentId, GrantKind.RETRIEVE).stream()
                .map(DocumentPrincipal::key)
                .toList();
    }

    /**
     * Whether this principal holds this access, asked directly rather than by listing and comparing.
     */
    public boolean holds(UUID documentId, DocumentPrincipal principal, GrantKind grantKind) {
        return principals(documentId, grantKind).contains(principal);
    }

    @Transactional
    public void deleteFor(UUID documentId) {
        documentEntitlementRepository.deleteByIngestedDocumentId(documentId);
    }

    private void replaceGrants(UUID documentId,
                               GrantKind grantKind,
                               Collection<DocumentPrincipal> principals,
                               UUID grantedBy) {
        Set<DocumentPrincipal> replacement = distinct(principals);

        if (replacement.isEmpty()) {
            throw new IllegalArgumentException(
                    "Replacing %s grants on document %s with none would leave it unreachable"
                            .formatted(grantKind, documentId));
        }

        log.debug("Replacing {} grants on document {}", grantKind, documentId);

        documentEntitlementRepository.deleteByIngestedDocumentIdAndGrantKind(documentId, grantKind);
        documentEntitlementRepository.flush();

        grant(documentId, grantKind, replacement, grantedBy);
    }

    /**
     * De-duplicates before the database has to. The unique constraint would reject a repeated
     * principal as a constraint violation, which is the right backstop but a poor way to find out
     * that a caller passed the same principal twice.
     */
    private static Set<DocumentPrincipal> distinct(Collection<DocumentPrincipal> principals) {
        return principals == null ? Set.of() : new LinkedHashSet<>(principals);
    }
}
