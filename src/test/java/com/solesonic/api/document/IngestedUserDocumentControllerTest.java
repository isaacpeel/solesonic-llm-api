package com.solesonic.api.document;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.ingestion.UriIngestionService;
import com.solesonic.service.security.ResourceOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The self-service collection, and above all that every one of its methods refuses a path naming
 * someone other than the caller.
 * <p>
 * {@code 403} for a mismatched path {@code userId} and {@code 404} for a document that is not in
 * this user's collection are two different answers to two different questions, and the pair is what
 * replaces the old unscoped {@code DELETE /documents/ingested/{id}} — which had no ownership check
 * of any kind.
 */
@ExtendWith(MockitoExtension.class)
class IngestedUserDocumentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private UriIngestionService uriIngestionService;

    @Mock
    private ResourceOwnershipService resourceOwnershipService;

    @InjectMocks
    private IngestedUserDocumentController ingestedUserDocumentController;

    private UUID userId;
    private UUID documentId;

    @BeforeEach
    void beforeEach() {
        userId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        // The matching-subject path succeeds unless a test below stubs otherwise.
        lenient().when(resourceOwnershipService.isOwner(eq(userId), any())).thenReturn(true);

        mockMvc = MockMvcBuilders.standaloneSetup(ingestedUserDocumentController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private IngestedDocumentSummary summary() {
        return new IngestedDocumentSummary(documentId,
                "notes.pdf",
                "application/pdf",
                1024L,
                DocumentSource.USER,
                RetrievalScope.USER,
                null,
                List.of("user:" + userId),
                null,
                DocumentStatus.COMPLETED,
                ZonedDateTime.now(),
                ZonedDateTime.now());
    }

    /**
     * A URI document is returned the moment it is queued, before anything has fetched it — so its
     * status is {@code QUEUED} and its size is still unknown.
     */
    private IngestedDocumentSummary queuedSummary() {
        return new IngestedDocumentSummary(documentId,
                "https://example.com/article",
                "text/html",
                0L,
                DocumentSource.URI,
                RetrievalScope.USER,
                null,
                List.of("user:" + userId),
                null,
                DocumentStatus.QUEUED,
                ZonedDateTime.now(),
                ZonedDateTime.now());
    }

    @Test
    void uploadCreatesAtUserScopeOwnedByThePathUser() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentService.queueForUser(any(), eq(userId))).thenReturn(summary());

        mockMvc.perform(multipart("/users/{userId}/documents", userId).file(file))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "http://localhost/users/" + userId + "/documents/" + documentId))
                .andExpect(jsonPath("$.scope").value("USER"));
    }

    @Test
    void uriIngestIsOwnedByThePathUser() throws Exception {
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(documentId);
        ingestedDocument.setDocumentStatus(DocumentStatus.QUEUED);

        when(uriIngestionService.queueForUser(eq("https://example.com/article"), eq(userId)))
                .thenReturn(ingestedDocument);
        when(ingestedDocumentService.summaryOf(ingestedDocument)).thenReturn(queuedSummary());

        mockMvc.perform(post("/users/{userId}/documents/uri", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uri\":\"https://example.com/article\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentStatus").value("QUEUED"));
    }

    @Test
    void listReturnsOnlyThisUsersDocuments() throws Exception {
        when(ingestedDocumentService.listForUser(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/users/{userId}/documents", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(documentId.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /**
     * A document that is not in this user's collection. The repository's {@code where} clause is
     * what makes it absent, and absence is a {@code 404} — the same answer whether it never existed
     * or belongs to someone else.
     */
    @Test
    void aDocumentInAnotherCollectionIsNotFound() throws Exception {
        when(ingestedDocumentService.getForUser(documentId, userId))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/users/{userId}/documents/{documentId}", userId, documentId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesTheDocument() throws Exception {
        mockMvc.perform(delete("/users/{userId}/documents/{documentId}", userId, documentId))
                .andExpect(status().isNoContent());

        verify(ingestedDocumentService).deleteForUser(documentId, userId);
    }

    @Test
    void renameSendsOnlyTheNewName() throws Exception {
        when(ingestedDocumentService.renameForUser(documentId, userId, "reading.pdf")).thenReturn(summary());

        mockMvc.perform(patch("/users/{userId}/documents/{documentId}", userId, documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"reading.pdf\"}"))
                .andExpect(status().isOk());

        verify(ingestedDocumentService).renameForUser(documentId, userId, "reading.pdf");
    }

    @Test
    void refreshRequeuesTheDocument() throws Exception {
        when(ingestedDocumentService.refreshForUser(documentId, userId)).thenReturn(summary());

        mockMvc.perform(post("/users/{userId}/documents/{documentId}/refresh", userId, documentId))
                .andExpect(status().isAccepted());

        verify(ingestedDocumentService).refreshForUser(documentId, userId);
    }

    /**
     * The {@code rag-admin} gate is an annotation, which a standalone {@code MockMvc} does not apply
     * — the same limitation {@code IngestedChatDocumentControllerTest} notes for its own
     * {@code promote/global}. What is covered here is routing and that ownership of the path
     * {@code userId} is still checked ahead of the promotion.
     */
    @Test
    void promoteToGlobalMovesTheDocumentIntoTheSharedCorpus() throws Exception {
        when(ingestedDocumentService.promoteToGlobal(documentId, userId)).thenReturn(summary());

        mockMvc.perform(post("/users/{userId}/documents/{documentId}/promote/global", userId, documentId))
                .andExpect(status().isOk());

        verify(ingestedDocumentService).promoteToGlobal(documentId, userId);
    }

    /**
     * Every method, not just the reads. The old {@code DELETE /documents/ingested/{id}} let any
     * authenticated caller delete any other user's document, and a guard that covered only some of
     * these would leave that hole open on the rest.
     */
    @Test
    void everyMethodRefusesAPathNamingAnotherUser() throws Exception {
        UUID otherUserId = UUID.randomUUID();
        when(resourceOwnershipService.isOwner(eq(otherUserId), any())).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2});

        mockMvc.perform(multipart("/users/{userId}/documents", otherUserId).file(file))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/users/{userId}/documents/uri", otherUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uri\":\"https://example.com/article\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/users/{userId}/documents", otherUserId))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/users/{userId}/documents/{documentId}", otherUserId, documentId))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/users/{userId}/documents/{documentId}", otherUserId, documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"stolen.pdf\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/users/{userId}/documents/{documentId}", otherUserId, documentId))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/users/{userId}/documents/{documentId}/refresh", otherUserId, documentId))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/users/{userId}/documents/{documentId}/promote/global", otherUserId, documentId))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ingestedDocumentService, uriIngestionService);
    }
}
