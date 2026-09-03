package com.solesonic.service.ingestion;

import com.solesonic.model.ingestion.DocumentEntitlement;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.GrantKind;
import com.solesonic.model.rag.PrincipalType;
import com.solesonic.repository.ingestion.DocumentEntitlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single derivation (plan §3.1): the only place chunk entitlement keys are produced, and the
 * only place a document's audience is changed.
 * <p>
 * What is worth pinning here is not that the repository is called — it is the two properties the
 * rest of the model rests on: that {@code MANAGE} never leaks into the chunk side, and that a
 * document with no grants yields no keys rather than quietly defaulting to something retrievable.
 */
@ExtendWith(MockitoExtension.class)
class DocumentEntitlementServiceTest {

    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final UUID UPLOADER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();

    @Mock
    private DocumentEntitlementRepository documentEntitlementRepository;

    @Captor
    private ArgumentCaptor<List<DocumentEntitlement>> entitlementsCaptor;

    private DocumentEntitlementService documentEntitlementService;

    @BeforeEach
    void beforeEach() {
        documentEntitlementService = new DocumentEntitlementService(documentEntitlementRepository);
    }

    /**
     * A chat document is retrievable by the conversation and managed by whoever uploaded it. Those
     * are two different principals, which is the whole reason {@code grant_kind} exists.
     */
    @Test
    void grantsRetrieveAndManageToDifferentPrincipals() {
        documentEntitlementService.grantOwnership(DOCUMENT_ID,
                DocumentPrincipal.chat(CHAT_ID),
                DocumentPrincipal.user(UPLOADER_ID),
                UPLOADER_ID);

        verify(documentEntitlementRepository, times(2)).saveAll(entitlementsCaptor.capture());

        List<List<DocumentEntitlement>> saved = entitlementsCaptor.getAllValues();

        DocumentEntitlement retrieve = saved.getFirst().getFirst();
        assertEquals(GrantKind.RETRIEVE, retrieve.getGrantKind());
        assertEquals(PrincipalType.CHAT, retrieve.getPrincipalType());
        assertEquals(CHAT_ID.toString(), retrieve.getPrincipalId());

        DocumentEntitlement manage = saved.get(1).getFirst();
        assertEquals(GrantKind.MANAGE, manage.getGrantKind());
        assertEquals(PrincipalType.USER, manage.getPrincipalType());
        assertEquals(UPLOADER_ID.toString(), manage.getPrincipalId());

        assertEquals(UPLOADER_ID, retrieve.getGrantedBy());
        assertEquals(DOCUMENT_ID, retrieve.getIngestedDocumentId());
    }

    /**
     * The chunk-side projection reads {@code RETRIEVE} and nothing else. If {@code MANAGE} reached
     * the array, every chunk of every chat document would carry its uploader's id as a retrievable
     * key — which is a disclosure, not just noise.
     */
    @Test
    void retrievalKeysCoverRetrieveGrantsOnly() {
        when(documentEntitlementRepository
                .findByIngestedDocumentIdAndGrantKind(DOCUMENT_ID, GrantKind.RETRIEVE))
                .thenReturn(List.of(entitlement(DocumentPrincipal.chat(CHAT_ID), GrantKind.RETRIEVE)));

        List<String> keys = documentEntitlementService.retrievalKeys(DOCUMENT_ID);

        assertEquals(List.of("chat:" + CHAT_ID), keys);
        verify(documentEntitlementRepository, never())
                .findByIngestedDocumentIdAndGrantKind(DOCUMENT_ID, GrantKind.MANAGE);
    }

    /**
     * Every principal shape, as the retrieval filters compare them literally. A key written under
     * one spelling and filtered under another retrieves nothing, silently.
     */
    @Test
    void retrievalKeysUseTheAgreedSpelling() {
        assertEquals("global", DocumentPrincipal.global().key());
        assertEquals("user:" + UPLOADER_ID, DocumentPrincipal.user(UPLOADER_ID).key());
        assertEquals("chat:" + CHAT_ID, DocumentPrincipal.chat(CHAT_ID).key());
        assertEquals("role:rag-admin", DocumentPrincipal.ragAdmin().key());
    }

    /**
     * A document whose grants have not been written yet must produce no keys at all.
     * <p>
     * The tempting alternative — treat "no grants" as global — is what
     * {@code DocumentService.scope(...)} used to do with a null scope, and it is why the column
     * disagreed with the chunks until {@code V3_25}. An unstamped chunk is an invisible document,
     * which surfaces as a bug report. A chunk defaulted to global is a disclosure, which does not.
     */
    @Test
    void retrievalKeysAreEmptyRatherThanGlobalWhenNothingIsGranted() {
        when(documentEntitlementRepository
                .findByIngestedDocumentIdAndGrantKind(DOCUMENT_ID, GrantKind.RETRIEVE))
                .thenReturn(List.of());

        assertTrue(documentEntitlementService.retrievalKeys(DOCUMENT_ID).isEmpty());
    }

    /**
     * Promotion clears the old audience before writing the new one, in that order — otherwise the
     * insert collides with the unique constraint on the grant it is replacing.
     */
    @Test
    void replacingRetrieveGrantsDeletesBeforeInserting() {
        documentEntitlementService.replaceRetrieveGrants(DOCUMENT_ID,
                List.of(DocumentPrincipal.user(UPLOADER_ID)),
                UPLOADER_ID);

        InOrder inOrder = inOrder(documentEntitlementRepository);
        inOrder.verify(documentEntitlementRepository)
                .deleteByIngestedDocumentIdAndGrantKind(DOCUMENT_ID, GrantKind.RETRIEVE);
        inOrder.verify(documentEntitlementRepository).flush();
        inOrder.verify(documentEntitlementRepository).saveAll(anyList());

        verify(documentEntitlementRepository, never())
                .deleteByIngestedDocumentIdAndGrantKind(DOCUMENT_ID, GrantKind.MANAGE);
    }

    /**
     * Replacing an audience with nothing would leave a document that exists, reports
     * {@code COMPLETED}, and can never be retrieved by anyone. Refused rather than written.
     */
    @Test
    void refusesToReplaceGrantsWithAnEmptySet() {
        assertThrows(IllegalArgumentException.class, () ->
                documentEntitlementService.replaceRetrieveGrants(DOCUMENT_ID, List.of(), UPLOADER_ID));

        verify(documentEntitlementRepository, never())
                .deleteByIngestedDocumentIdAndGrantKind(DOCUMENT_ID, GrantKind.RETRIEVE);
    }

    /**
     * The same principal twice is a caller error the unique constraint would catch as a violation.
     * Collapsing it first turns a stack trace into a no-op.
     */
    @Test
    void collapsesARepeatedPrincipal() {
        documentEntitlementService.grant(DOCUMENT_ID,
                GrantKind.RETRIEVE,
                List.of(DocumentPrincipal.user(UPLOADER_ID), DocumentPrincipal.user(UPLOADER_ID)),
                UPLOADER_ID);

        verify(documentEntitlementRepository).saveAll(entitlementsCaptor.capture());
        assertEquals(1, entitlementsCaptor.getValue().size());
    }

    /**
     * Granting nothing writes nothing, rather than issuing an empty insert.
     */
    @Test
    void grantingNoPrincipalsTouchesNothing() {
        documentEntitlementService.grant(DOCUMENT_ID, GrantKind.RETRIEVE, List.of(), UPLOADER_ID);

        verify(documentEntitlementRepository, never()).saveAll(anyList());
    }

    @Test
    void reportsWhetherAPrincipalHoldsAGrant() {
        when(documentEntitlementRepository
                .findByIngestedDocumentIdAndGrantKind(DOCUMENT_ID, GrantKind.MANAGE))
                .thenReturn(List.of(entitlement(DocumentPrincipal.user(UPLOADER_ID), GrantKind.MANAGE)));

        assertTrue(documentEntitlementService.holds(DOCUMENT_ID,
                DocumentPrincipal.user(UPLOADER_ID), GrantKind.MANAGE));
        assertFalse(documentEntitlementService.holds(DOCUMENT_ID,
                DocumentPrincipal.user(UUID.randomUUID()), GrantKind.MANAGE));
    }

    /**
     * A GLOBAL grant carries a sentinel rather than a null id, because SQL uniqueness does not
     * constrain nulls — two "granted to everyone" rows would both be accepted.
     */
    @Test
    void globalPrincipalCarriesASentinelRatherThanNull() {
        assertEquals(DocumentPrincipal.GLOBAL_SENTINEL, DocumentPrincipal.global().id());
        assertThrows(IllegalArgumentException.class, () -> new DocumentPrincipal(PrincipalType.GLOBAL, null));
        assertThrows(IllegalArgumentException.class, () -> new DocumentPrincipal(PrincipalType.USER, " "));
    }

    private DocumentEntitlement entitlement(DocumentPrincipal principal, GrantKind grantKind) {
        return new DocumentEntitlement(DOCUMENT_ID, principal, grantKind, UPLOADER_ID);
    }
}
