package com.solesonic.izzybot.api.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.solesonic.izzybot.model.ollama.OllamaModel;
import com.solesonic.izzybot.service.ollama.OllamaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
public class OllamaControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Mock
    private OllamaService ollamaService;

    @InjectMocks
    private OllamaController ollamaController;

    private UUID modelId;
    private OllamaModel ollamaModel;
    private List<OllamaModel> ollamaModels;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();

        // Set up OllamaModel
        ollamaModel = new OllamaModel();
        ollamaModel.setId(modelId);
        ollamaModel.setName("llama3");
        ollamaModel.setCensored(false);
        ollamaModel.setEmbedding(true);
        ollamaModel.setTools(true);
        ollamaModel.setVision(false);
        ollamaModel.setCreated(ZonedDateTime.now());
        ollamaModel.setUpdated(ZonedDateTime.now());

        // Set up list of OllamaModels
        ollamaModels = new ArrayList<>();
        ollamaModels.add(ollamaModel);

        // Set up MockMvc
        mockMvc = MockMvcBuilders.standaloneSetup(ollamaController).build();
    }

    @Test
    void testModels() throws Exception {
        // Arrange
        when(ollamaService.models()).thenReturn(ollamaModels);

        // Act & Assert
        mockMvc.perform(get("/izzybot/ollama/models"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(modelId.toString()))
                .andExpect(jsonPath("$[0].name").value("llama3"))
                .andExpect(jsonPath("$[0].censored").value(false))
                .andExpect(jsonPath("$[0].embedding").value(true))
                .andExpect(jsonPath("$[0].tools").value(true))
                .andExpect(jsonPath("$[0].vision").value(false));
    }

    @Test
    void testModel() throws Exception {
        // Arrange
        when(ollamaService.get(modelId)).thenReturn(ollamaModel);

        // Act & Assert
        mockMvc.perform(get("/izzybot/ollama/models/{id}", modelId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(modelId.toString()))
                .andExpect(jsonPath("$.name").value("llama3"))
                .andExpect(jsonPath("$.censored").value(false))
                .andExpect(jsonPath("$.embedding").value(true))
                .andExpect(jsonPath("$.tools").value(true))
                .andExpect(jsonPath("$.vision").value(false));
    }

    @Test
    void testSave() throws Exception {
        // Arrange
        when(ollamaService.save(any(OllamaModel.class))).thenReturn(ollamaModel);

        // Act & Assert
        mockMvc.perform(post("/izzybot/ollama/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ollamaModel)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(modelId.toString()))
                .andExpect(jsonPath("$.name").value("llama3"))
                .andExpect(jsonPath("$.censored").value(false))
                .andExpect(jsonPath("$.embedding").value(true))
                .andExpect(jsonPath("$.tools").value(true))
                .andExpect(jsonPath("$.vision").value(false));
    }

    @Test
    void testUpdate() throws Exception {
        // Arrange
        when(ollamaService.update(eq(modelId), any(OllamaModel.class))).thenReturn(ollamaModel);

        // Act & Assert
        mockMvc.perform(put("/izzybot/ollama/models/{id}", modelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ollamaModel)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(modelId.toString()))
                .andExpect(jsonPath("$.name").value("llama3"))
                .andExpect(jsonPath("$.censored").value(false))
                .andExpect(jsonPath("$.embedding").value(true))
                .andExpect(jsonPath("$.tools").value(true))
                .andExpect(jsonPath("$.vision").value(false));
    }
}