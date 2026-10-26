package com.solesonic.api.document;

import com.solesonic.model.VectorSearch;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.ingestion.StatusHistoryService;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What remains under {@code /documents} itself. Creation and CRUD moved to the two scoped
 * collections, so the routes this used to cover — {@code /documents/data/upload} and
 * {@code /documents/uri} — are gone rather than deprecated.
 */
@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private VectorStoreService vectorStoreService;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LlmApiKey LLM_API_KEY = new LlmApiKey("sk-user-0000000001");

    @Mock
    private StatusHistoryService statusHistoryService;

    @Mock
    private UserLlmKeyService userLlmKeyService;

    @Mock
    private UserRequestContext userRequestContext;

    @InjectMocks
    private DocumentController documentController;

    @BeforeEach
    void beforeEach() {
        mockMvc = MockMvcBuilders.standaloneSetup(documentController).build();
    }

    @Test
    void searchEmbedsTheQueryOnTheCallersKey() throws Exception {
        when(userRequestContext.getUserId()).thenReturn(USER_ID);
        when(userLlmKeyService.requireKey(USER_ID)).thenReturn(LLM_API_KEY);
        when(vectorStoreService.findSimilarDocuments(any(VectorSearch.class), eq(LLM_API_KEY)))
                .thenReturn(List.of(new Document("the handbook says so")));

        mockMvc.perform(post("/documents/data/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"handbook\",\"similarityThreshold\":0.7,\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("the handbook says so"));
    }

    @Test
    void processQueueDrainsTheIngestionQueue() throws Exception {
        mockMvc.perform(post("/documents/processQueue"))
                .andExpect(status().isAccepted());

        verify(statusHistoryService).processQueued();
    }
}
