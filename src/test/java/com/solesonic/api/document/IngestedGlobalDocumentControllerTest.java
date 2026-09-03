package com.solesonic.api.document;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.ingestion.UriIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
 * The shared collection's routing and response shapes. The {@code rag-admin} gate is an annotation,
 * which a standalone {@code MockMvc} does not apply — {@link IngestedDocumentMethodSecurityTest}
 * covers that it is enforced, and
 * as a {@code 403}.
 */
@ExtendWith(MockitoExtension.class)
class IngestedGlobalDocumentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private UriIngestionService uriIngestionService;

    @InjectMocks
    private IngestedGlobalDocumentController ingestedGlobalDocumentController;

    private UUID documentId;

    @BeforeEach
    void beforeEach() {
        documentId = UUID.randomUUID();
        mockMvc = MockMvcBuilders.standaloneSetup(ingestedGlobalDocumentController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private IngestedDocumentSummary summary() {
        return new IngestedDocumentSummary(documentId,
                "handbook.pdf",
                "application/pdf",
                2048L,
                DocumentSource.USER,
                RetrievalScope.GLOBAL,
                null,
                List.of("global"),
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
                RetrievalScope.GLOBAL,
                null,
                List.of("global"),
                null,
                DocumentStatus.QUEUED,
                ZonedDateTime.now(),
                ZonedDateTime.now());
    }

    /**
     * No {@code scope} parameter reaches the service from this route — the collection is the scope,
     * which is what makes "created at the wrong scope" unexpressible rather than merely unlikely.
     */
    @Test
    void uploadCreatesAtGlobalScopeWithNoOwner() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "handbook.pdf", "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentService.queueGlobal(any(), any())).thenReturn(summary());

        mockMvc.perform(multipart("/documents/global").file(file))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/documents/global/" + documentId))
                .andExpect(jsonPath("$.id").value(documentId.toString()))
                .andExpect(jsonPath("$.scope").value("GLOBAL"))
                .andExpect(jsonPath("$.entitlements[0]").value("global"));
    }

    /**
     * {@code fileData} is not a field on the summary, so there is no shape in which the uploaded
     * bytes could come back out of the API.
     */
    @Test
    void theResponseCarriesNoFileContent() throws Exception {
        when(ingestedDocumentService.getGlobal(documentId)).thenReturn(summary());

        mockMvc.perform(get("/documents/global/{id}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileData").doesNotExist())
                .andExpect(jsonPath("$.fileSizeBytes").value(2048));
    }

    @Test
    void uriIngestQueuesAtGlobalScope() throws Exception {
        IngestedDocument ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(documentId);
        ingestedDocument.setFileName("https://example.com/article");
        ingestedDocument.setDocumentStatus(DocumentStatus.QUEUED);

        when(uriIngestionService.queueGlobal(eq("https://example.com/article"), any()))
                .thenReturn(ingestedDocument);
        when(ingestedDocumentService.summaryOf(ingestedDocument)).thenReturn(queuedSummary());

        mockMvc.perform(post("/documents/global/uri")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uri\":\"https://example.com/article\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "http://localhost/documents/global/" + documentId))
                .andExpect(jsonPath("$.documentStatus").value("QUEUED"));
    }

    @Test
    void listIsPaginatedAndTakesOnlyTheWindow() throws Exception {
        when(ingestedDocumentService.listGlobal(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(1, 5), 12));

        mockMvc.perform(get("/documents/global")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "fileName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(documentId.toString()))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(12));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ingestedDocumentService).listGlobal(pageableCaptor.capture());

        // A caller-supplied sort is dropped: the ordering belongs to the repository query.
        assertThat(pageableCaptor.getValue().getSort().isSorted()).isFalse();
    }

    @Test
    void renameSendsOnlyTheNewName() throws Exception {
        when(ingestedDocumentService.renameGlobal(documentId, "onboarding.pdf")).thenReturn(summary());

        mockMvc.perform(patch("/documents/global/{id}", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"onboarding.pdf\"}"))
                .andExpect(status().isOk());

        verify(ingestedDocumentService).renameGlobal(documentId, "onboarding.pdf");
    }

    @Test
    void deleteRemovesTheDocument() throws Exception {
        mockMvc.perform(delete("/documents/global/{id}", documentId))
                .andExpect(status().isNoContent());

        verify(ingestedDocumentService).deleteGlobal(documentId);
    }

    @Test
    void refreshRequeuesTheDocument() throws Exception {
        when(ingestedDocumentService.refreshGlobal(documentId)).thenReturn(summary());

        mockMvc.perform(post("/documents/global/{id}/refresh", documentId))
                .andExpect(status().isAccepted());

        verify(ingestedDocumentService).refreshGlobal(documentId);
    }
}
