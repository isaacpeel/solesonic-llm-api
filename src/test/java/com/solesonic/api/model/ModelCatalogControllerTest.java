package com.solesonic.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.solesonic.model.llm.LlmModel;
import com.solesonic.service.model.ModelCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ModelCatalogControllerTest {

    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl ->
                    incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Mock
    private ModelCatalogService modelCatalogService;

    @InjectMocks
    private ModelCatalogController modelCatalogController;

    private UUID modelId;
    private LlmModel llmModel;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();

        llmModel = new LlmModel();
        llmModel.setId(modelId);
        llmModel.setName("llama3");
        llmModel.setCensored(false);
        llmModel.setCreated(ZonedDateTime.now());
        llmModel.setUpdated(ZonedDateTime.now());

        mockMvc = MockMvcBuilders.standaloneSetup(modelCatalogController).build();
    }

    @Test
    void testModels() throws Exception {
        when(modelCatalogService.models()).thenReturn(List.of(llmModel));

        mockMvc.perform(get("/models"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(modelId.toString()))
                .andExpect(jsonPath("$[0].name").value("llama3"))
                .andExpect(jsonPath("$[0].censored").value(false));
    }

    @Test
    void testModel() throws Exception {
        when(modelCatalogService.get(modelId)).thenReturn(llmModel);

        mockMvc.perform(get("/models/{id}", modelId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(modelId.toString()))
                .andExpect(jsonPath("$.name").value("llama3"))
                .andExpect(jsonPath("$.censored").value(false));
    }

    @Test
    void testSave() throws Exception {
        when(modelCatalogService.save(any(LlmModel.class))).thenReturn(llmModel);

        mockMvc.perform(post("/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(llmModel)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(modelId.toString()))
                .andExpect(jsonPath("$.name").value("llama3"));
    }

    @Test
    void testUpdate() throws Exception {
        when(modelCatalogService.update(eq(modelId), any(LlmModel.class))).thenReturn(llmModel);

        mockMvc.perform(put("/models/{id}", modelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(llmModel)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(modelId.toString()))
                .andExpect(jsonPath("$.name").value("llama3"));
    }

    @Test
    void testDelete() throws Exception {
        mockMvc.perform(delete("/models/{id}", modelId))
                .andExpect(status().isNoContent());

        verify(modelCatalogService).delete(modelId);
    }
}
