package com.solesonic.api.ollama;

import com.solesonic.model.document.UriIngestRequest;
import com.solesonic.model.training.TrainingDocument;
import com.solesonic.service.rag.TrainingDocumentService;
import com.solesonic.service.rag.UriTrainingService;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    private static final String TEST_URI = "https://example.com/article";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private MockMvc mockMvc;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private TrainingDocumentService trainingDocumentService;

    @Mock
    private UriTrainingService uriTrainingService;

    @InjectMocks
    private DocumentController documentController;

    @BeforeEach
    void beforeEach() {
        mockMvc = MockMvcBuilders.standaloneSetup(documentController).build();
    }

    @Test
    void test_handleUriIngest() throws Exception {
        UUID trainingDocumentId = UUID.randomUUID();

        TrainingDocument trainingDocument = new TrainingDocument();
        trainingDocument.setId(trainingDocumentId);
        trainingDocument.setFileName(TEST_URI);

        when(uriTrainingService.queue(eq(TEST_URI))).thenReturn(trainingDocument);

        UriIngestRequest uriIngestRequest = new UriIngestRequest(TEST_URI);

        mockMvc.perform(post("/documents/uri")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(uriIngestRequest)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "http://localhost/trainingdocuments/" + trainingDocumentId))
                .andExpect(jsonPath("$.id").value(trainingDocumentId.toString()));
    }
}
