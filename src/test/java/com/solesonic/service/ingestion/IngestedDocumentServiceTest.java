package com.solesonic.service.ingestion;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.ingestion.VectorDocument;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.model.rag.GrantKind;
import com.solesonic.model.rag.PrincipalType;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.repository.chat.ChatAttachmentRepository;
import com.solesonic.repository.chat.ChatRepository;
import com.solesonic.repository.ingestion.DocumentEntitlementRepository;
import com.solesonic.repository.ingestion.IngestedDocumentContentRepository;
import com.solesonic.repository.ingestion.IngestedDocumentRepository;
import com.solesonic.repository.ingestion.StatusHistoryRepository;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one method family, parameterised by principal.
 * <p>
 * What is worth pinning is that the right <em>grant</em> is asked for in each case: a
 * {@code GLOBAL}/{@code USER} document is fetched by its retrieve grant, and a chat document by the
 * caller's manage grant — because a chat document is retrievable by the conversation but managed by
 * the person who uploaded it, so asking the retrieve question would hide a user's own document from
 * the endpoints meant to manage it.
 */
@ExtendWith(MockitoExtension.class)
class IngestedDocumentServiceTest {

    private static final Pageable PAGE = PageRequest.of(0, 20);

    @Mock
    private IngestedDocumentRepository ingestedDocumentRepository;

    @Mock
    private StatusHistoryRepository statusHistoryRepository;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private DocumentEntitlementService documentEntitlementService;

    @Mock
    private DocumentEntitlementRepository documentEntitlementRepository;

    @Mock
    private IngestedDocumentContentRepository ingestedDocumentContentRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private ChatAttachmentRepository chatAttachmentRepository;

    private IngestedDocumentService ingestedDocumentService;

    private UUID documentId;
    private UUID userId;
    private UUID chatId;

    @BeforeEach
    void beforeEach() {
        ingestedDocumentService = new IngestedDocumentService(ingestedDocumentRepository,
                statusHistoryRepository,
                vectorStoreService,
                documentEntitlementService,
                documentEntitlementRepository,
                ingestedDocumentContentRepository,
                chatRepository,
                chatAttachmentRepository);

        documentId = UUID.randomUUID();
        userId = UUID.randomUUID();
        chatId = UUID.randomUUID();
    }

    private IngestedDocument document() {
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(documentId);
        ingestedDocument.setFileName("notes.pdf");
        ingestedDocument.setDocumentSource(DocumentSource.USER);
        ingestedDocument.setMetadata(new HashMap<>());
        return ingestedDocument;
    }

    private IngestedDocument chatDocument() {
        IngestedDocument ingestedDocument = document();
        ingestedDocument.setDocumentSource(DocumentSource.CHAT);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestedDocument.CHAT_ID, chatId.toString());
        ingestedDocument.setMetadata(metadata);

        return ingestedDocument;
    }

    private void grantedRetrieveTo(DocumentPrincipal principal) {
        lenient().when(documentEntitlementService.principals(documentId, GrantKind.RETRIEVE))
                .thenReturn(List.of(principal));
    }

    private void savesTheRow() {
        when(ingestedDocumentRepository.save(any(IngestedDocument.class))).thenAnswer(invocation -> {
            IngestedDocument saved = invocation.getArgument(0);
            saved.setId(documentId);
            return saved;
        });
    }

    @Test
    void listsTheSharedCorpusByItsGlobalRetrieveGrant() {
        when(ingestedDocumentRepository.findAllRetrievableBy(PrincipalType.GLOBAL,
                DocumentPrincipal.GLOBAL_SENTINEL, PAGE))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(ingestedDocumentService.listGlobal(PAGE)).isEmpty();
    }

    @Test
    void listsAUsersLibraryByTheirRetrieveGrant() {
        when(ingestedDocumentRepository.findAllRetrievableBy(PrincipalType.USER, userId.toString(), PAGE))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(ingestedDocumentService.listForUser(userId, PAGE)).isEmpty();
    }

    /**
     * The headline query (§6.2). A null chat id spans every conversation the caller has uploaded to.
     */
    @Test
    void listsEveryChatDocumentTheUserManages() {
        when(ingestedDocumentRepository.findAllChatDocumentsManagedBy(userId.toString(), null, null, PAGE))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(ingestedDocumentService.listChatDocuments(userId, null, null, PAGE)).isEmpty();
    }

    @Test
    void narrowsTheChatDocumentListingToOneConversationAndStatus() {
        when(ingestedDocumentRepository.findAllChatDocumentsManagedBy(
                userId.toString(), chatId.toString(), DocumentStatus.FAILED, PAGE))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(ingestedDocumentService
                .listChatDocuments(userId, chatId, DocumentStatus.FAILED, PAGE)).isEmpty();
    }

    @Test
    void aGlobalDocumentIsFetchedByItsRetrieveGrant() {
        when(ingestedDocumentRepository.findRetrievableBy(documentId, PrincipalType.GLOBAL,
                DocumentPrincipal.GLOBAL_SENTINEL))
                .thenReturn(Optional.of(document()));
        grantedRetrieveTo(DocumentPrincipal.global());

        IngestedDocumentSummary summary = ingestedDocumentService.getGlobal(documentId);

        assertThat(summary.scope()).isEqualTo(RetrievalScope.GLOBAL);
        assertThat(summary.entitlements()).containsExactly("global");
    }

    @Test
    void aDocumentUnderAnotherPrincipalIsNotFound() {
        when(ingestedDocumentRepository.findRetrievableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestedDocumentService.getForUser(documentId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    /**
     * The distinction that makes the chat collection work: a chat document is retrievable by the
     * conversation, so the caller's own grant on it is {@code MANAGE}, not {@code RETRIEVE}.
     */
    @Test
    void aChatDocumentIsFetchedByTheCallersManageGrant() {
        when(ingestedDocumentRepository.findManageableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(chatDocument()));
        grantedRetrieveTo(DocumentPrincipal.chat(chatId));

        IngestedDocumentSummary summary = ingestedDocumentService.getChatDocument(documentId, userId);

        assertThat(summary.scope()).isEqualTo(RetrievalScope.CHAT);
        assertThat(summary.chatId()).isEqualTo(chatId);
        assertThat(summary.entitlements()).containsExactly("chat:" + chatId);

        verify(ingestedDocumentRepository, never()).findRetrievableBy(any(), any(), any());
    }

    @Test
    void aGlobalUploadIsManagedByTheRoleRatherThanTheUploader() {
        MockMultipartFile file = new MockMultipartFile("file", "handbook.pdf", "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentRepository.findGlobalByFileName("handbook.pdf")).thenReturn(Optional.empty());
        savesTheRow();
        grantedRetrieveTo(DocumentPrincipal.global());

        ingestedDocumentService.queueGlobal(file, userId);

        verify(documentEntitlementService).grantOwnership(documentId,
                DocumentPrincipal.global(),
                DocumentPrincipal.ragAdmin(),
                userId);

        verify(ingestedDocumentContentRepository).save(any());
    }

    /**
     * De-duplication is only ever between shared documents: two users uploading {@code notes.pdf} to
     * their own libraries are two documents, and reusing one row would hand the second uploader the
     * first one's.
     */
    @Test
    void aGlobalUploadOfAnExistingNameReturnsTheExistingDocument() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentRepository.findGlobalByFileName("notes.pdf")).thenReturn(Optional.of(document()));
        grantedRetrieveTo(DocumentPrincipal.global());

        ingestedDocumentService.queueGlobal(file, userId);

        verify(ingestedDocumentRepository, never()).save(any(IngestedDocument.class));
        verify(documentEntitlementService, never()).grantOwnership(any(), any(), any(), any());
    }

    /**
     * A document uploaded straight to a conversation is retrievable by the chat and managed by the
     * uploader — the split that makes "all my chat documents" answerable at all.
     */
    @Test
    void aChatUploadIsRetrievableByTheChatAndManagedByTheUploader() {
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2});

        savesTheRow();
        grantedRetrieveTo(DocumentPrincipal.chat(chatId));

        ingestedDocumentService.queueForChat(file, chatId, userId);

        verify(documentEntitlementService).grantOwnership(documentId,
                DocumentPrincipal.chat(chatId),
                DocumentPrincipal.user(userId),
                userId);
    }

    /**
     * An attachment-sourced row opens at {@code IN_PROGRESS} and stores no bytes: the attachment
     * holds the only copy until a promotion severs the two.
     */
    @Test
    void beginningChatIngestionOpensAnInProgressRowWithNoContent() {
        savesTheRow();

        UUID chatAttachmentId = UUID.randomUUID();

        IngestedDocument saved = ingestedDocumentService.beginChatIngestion(
                chatId, userId, chatAttachmentId, "notes.pdf", "application/pdf");

        assertThat(saved.getDocumentStatus()).isEqualTo(DocumentStatus.IN_PROGRESS);
        assertThat(saved.getDocumentSource()).isEqualTo(DocumentSource.CHAT);
        assertThat(saved.getMetadata()).containsEntry(IngestedDocument.CHAT_ID, chatId.toString());
        assertThat(saved.getMetadata())
                .containsEntry(IngestedDocument.CHAT_ATTACHMENT_ID, chatAttachmentId.toString());

        verify(documentEntitlementService).grantOwnership(documentId,
                DocumentPrincipal.chat(chatId),
                DocumentPrincipal.user(userId),
                userId);

        verify(ingestedDocumentContentRepository, never()).save(any());
    }

    /**
     * The whole of a promotion: the bytes are copied off the attachment, the source stops being
     * {@code CHAT}, the audience is replaced, the chat provenance is cleared, and the chunks are
     * re-pointed in place rather than re-embedded.
     * <p>
     * Clearing {@code CHAT_ID} is what makes the promoted document survive its origin conversation
     * being deleted — teardown is keyed on provenance, so a document that kept the key would lose
     * its chunks with the chat and leave a {@code COMPLETED} document that retrieves nothing.
     */
    @Test
    void promotingToAUsersLibraryMaterializesBytesAndClearsChatProvenance() {
        UUID chatAttachmentId = UUID.randomUUID();

        IngestedDocument ingestedDocument = chatDocument();
        ingestedDocument.getMetadata().put(IngestedDocument.CHAT_ATTACHMENT_ID, chatAttachmentId.toString());

        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setId(chatAttachmentId);
        chatAttachment.setFileData("attached text".getBytes());

        when(ingestedDocumentRepository.findManageableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(ingestedDocument));
        when(statusHistoryRepository.findByDocumentId(documentId))
                .thenReturn(List.of(DocumentStatus.COMPLETED));
        when(ingestedDocumentContentRepository.existsByIngestedDocumentId(documentId)).thenReturn(false);
        when(chatAttachmentRepository.findById(chatAttachmentId)).thenReturn(Optional.of(chatAttachment));
        when(documentEntitlementService.retrievalKeys(documentId)).thenReturn(List.of("user:" + userId));
        grantedRetrieveTo(DocumentPrincipal.user(userId));

        ingestedDocumentService.promoteToUser(documentId, userId);

        verify(ingestedDocumentContentRepository).save(any());
        verify(documentEntitlementService)
                .replaceRetrieveGrants(documentId, List.of(DocumentPrincipal.user(userId)), userId);
        verify(vectorStoreService).promoteChunks(documentId, List.of("user:" + userId));

        assertThat(ingestedDocument.getDocumentSource()).isEqualTo(DocumentSource.USER);
        assertThat(ingestedDocument.getMetadata()).doesNotContainKey(IngestedDocument.CHAT_ID);
        assertThat(ingestedDocument.getMetadata()).doesNotContainKey(IngestedDocument.CHAT_ATTACHMENT_ID);

        // Management is untouched: the caller already manages it, which is how they were allowed to
        // ask in the first place.
        verify(documentEntitlementService, never()).replaceManageGrants(any(), anyList(), any());
    }

    @Test
    void promotingToTheSharedCorpusHandsManagementToTheRole() {
        IngestedDocument ingestedDocument = chatDocument();

        when(ingestedDocumentRepository.findManageableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(ingestedDocument));
        when(statusHistoryRepository.findByDocumentId(documentId))
                .thenReturn(List.of(DocumentStatus.COMPLETED));
        when(ingestedDocumentRepository.findGlobalByFileName("notes.pdf")).thenReturn(Optional.empty());
        when(ingestedDocumentContentRepository.existsByIngestedDocumentId(documentId)).thenReturn(true);
        when(documentEntitlementService.retrievalKeys(documentId)).thenReturn(List.of("global"));
        grantedRetrieveTo(DocumentPrincipal.global());

        ingestedDocumentService.promoteToGlobal(documentId, userId);

        verify(documentEntitlementService)
                .replaceRetrieveGrants(documentId, List.of(DocumentPrincipal.global()), userId);
        verify(documentEntitlementService)
                .replaceManageGrants(documentId, List.of(DocumentPrincipal.ragAdmin()), userId);
    }

    /**
     * A promotion mid-ingest would race the chunk stamping it is trying to rewrite, leaving some
     * chunks on the old audience.
     */
    @Test
    void refusesToPromoteADocumentThatIsStillIngesting() {
        when(ingestedDocumentRepository.findManageableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(chatDocument()));
        when(statusHistoryRepository.findByDocumentId(documentId))
                .thenReturn(List.of(DocumentStatus.IN_PROGRESS));

        assertThatThrownBy(() -> ingestedDocumentService.promoteToUser(documentId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        verify(documentEntitlementService, never()).replaceRetrieveGrants(any(), anyList(), any());
        verify(vectorStoreService, never()).promoteChunks(any(), anyList());
    }

    /**
     * {@code queueGlobal} de-duplicates by returning the existing row. Promotion cannot: it already
     * has a different row, and silently merging the two would discard one.
     */
    @Test
    void refusesToPromoteOverAnExistingGlobalName() {
        IngestedDocument existing = document();
        existing.setId(UUID.randomUUID());

        when(ingestedDocumentRepository.findManageableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(chatDocument()));
        when(statusHistoryRepository.findByDocumentId(documentId))
                .thenReturn(List.of(DocumentStatus.COMPLETED));
        when(ingestedDocumentRepository.findGlobalByFileName("notes.pdf")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> ingestedDocumentService.promoteToGlobal(documentId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        verify(documentEntitlementService, never()).replaceRetrieveGrants(any(), anyList(), any());
    }

    /**
     * Promoting must not leave a document whose bytes are gone: it would survive its conversation
     * only to fail every {@code refresh} afterwards.
     */
    @Test
    void refusesToPromoteADocumentWithNeitherContentNorAttachment() {
        when(ingestedDocumentRepository.findManageableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(chatDocument()));
        when(statusHistoryRepository.findByDocumentId(documentId))
                .thenReturn(List.of(DocumentStatus.COMPLETED));
        when(ingestedDocumentContentRepository.existsByIngestedDocumentId(documentId)).thenReturn(false);

        assertThatThrownBy(() -> ingestedDocumentService.promoteToUser(documentId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    /**
     * The single teardown: chunks first, then status history, then the row — whose content and grant
     * rows go by {@code ON DELETE CASCADE}.
     */
    @Test
    void deletingADocumentTakesItsChunksAndStatusHistory() {
        IngestedDocument ingestedDocument = document();
        VectorDocument vectorDocument = new VectorDocument();

        when(ingestedDocumentRepository.findRetrievableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(ingestedDocument));
        when(vectorStoreService.findByIngestedDocumentId(documentId)).thenReturn(List.of(vectorDocument));

        ingestedDocumentService.deleteForUser(documentId, userId);

        verify(vectorStoreService).delete(List.of(vectorDocument));
        verify(statusHistoryRepository).deleteByDocumentId(documentId);
        verify(ingestedDocumentRepository).delete(ingestedDocument);
    }

    /**
     * Teardown is keyed on provenance, so it reaches a document whatever its audience — and a
     * promoted document, which cleared that key, is deliberately not reached.
     */
    @Test
    void deletingAConversationTakesEveryDocumentThatCameFromIt() {
        IngestedDocument ingestedDocument = chatDocument();

        when(ingestedDocumentRepository.findByChatId(chatId.toString()))
                .thenReturn(List.of(ingestedDocument));
        when(vectorStoreService.findByIngestedDocumentId(documentId)).thenReturn(List.of());

        ingestedDocumentService.deleteByChatId(chatId);

        verify(ingestedDocumentRepository).delete(ingestedDocument);
    }

    @Test
    void renamingDoesNotReEmbed() {
        IngestedDocument ingestedDocument = document();

        when(ingestedDocumentRepository.findRetrievableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(ingestedDocument));
        when(ingestedDocumentRepository.save(ingestedDocument)).thenReturn(ingestedDocument);
        grantedRetrieveTo(DocumentPrincipal.user(userId));

        ingestedDocumentService.renameForUser(documentId, userId, "  onboarding.pdf  ");

        assertThat(ingestedDocument.getFileName()).isEqualTo("onboarding.pdf");
        verify(vectorStoreService, never()).delete(anyList());
    }

    @Test
    void refusesABlankName() {
        assertThatThrownBy(() -> ingestedDocumentService.renameForUser(documentId, userId, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(ingestedDocumentRepository, never()).findRetrievableBy(any(), any(), any());
    }

    @Test
    void refreshClearsChunksAndRequeues() {
        IngestedDocument ingestedDocument = document();

        when(ingestedDocumentRepository.findRetrievableBy(documentId, PrincipalType.USER, userId.toString()))
                .thenReturn(Optional.of(ingestedDocument));
        when(vectorStoreService.findByIngestedDocumentId(documentId)).thenReturn(List.of());
        when(ingestedDocumentRepository.save(ingestedDocument)).thenReturn(ingestedDocument);
        grantedRetrieveTo(DocumentPrincipal.user(userId));

        ingestedDocumentService.refreshForUser(documentId, userId);

        assertThat(ingestedDocument.getDocumentStatus()).isEqualTo(DocumentStatus.QUEUED);
    }
}
