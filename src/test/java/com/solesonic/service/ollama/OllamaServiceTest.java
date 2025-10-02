package com.solesonic.service.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solesonic.exception.ChatException;
import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.repository.ollama.OllamaModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.ollama.api.OllamaApi;
import org.testcontainers.shaded.com.fasterxml.jackson.core.type.TypeReference;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OllamaServiceTest {

    @Mock
    private OllamaApi ollamaApi;

    @Mock
    private OllamaModelRepository modelRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OllamaService ollamaService;

    private UUID modelId;
    private OllamaModel ollamaModel;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();

        // Set up OllamaModel
        ollamaModel = new OllamaModel();
        ollamaModel.setId(modelId);
        ollamaModel.setName("llama3");
        ollamaModel.setCreated(ZonedDateTime.now());
        ollamaModel.setUpdated(ZonedDateTime.now());
    }

    @Test
    void testGet() {
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(ollamaModel));
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);

        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.name()).thenReturn("llama3");

        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(List.of(nativeModel));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        OllamaApi.ShowModelResponse showModelResponse = mock(OllamaApi.ShowModelResponse.class);
        when(ollamaApi.showModel(any())).thenReturn(showModelResponse);

        OllamaModel result = ollamaService.get(modelId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(modelId);
        assertThat(result.getName()).isEqualTo("llama3");
        verify(modelRepository).findById(modelId);
        verify(modelRepository).save(any(OllamaModel.class));
        verify(ollamaApi).listModels();
    }

    @Test
    void testGetNotFound() {
        
        when(modelRepository.findById(modelId)).thenReturn(Optional.empty());

         
        assertThatThrownBy(() -> ollamaService.get(modelId))
                .isInstanceOf(ChatException.class)
                .hasMessage("OLLAMA MODEL NOT FOUND");
        verify(modelRepository).findById(modelId);
    }

    @Test
    void testSave() {
        
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);

        // Mock OllamaApi.Model
        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.name()).thenReturn("llama3");

        // Mock OllamaApi.ListModelResponse
        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(List.of(nativeModel));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        
        OllamaModel result = ollamaService.save(ollamaModel);

        
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(modelId);
        assertThat(result.getName()).isEqualTo("llama3");
        verify(modelRepository).save(ollamaModel);
        verify(ollamaApi).listModels();
    }

    @Test
    void testUpdate() {
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);

        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.name()).thenReturn("llama3");

        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(List.of(nativeModel));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        OllamaModel result = ollamaService.update(modelId, ollamaModel);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(modelId);
        assertThat(result.getName()).isEqualTo("llama3");
        verify(modelRepository).save(ollamaModel);
        verify(ollamaApi).listModels();
    }

    @Test
    void testModels() {
        OllamaModel model1 = new OllamaModel();
        model1.setId(UUID.randomUUID());
        model1.setName("llama3");

        OllamaModel model2 = new OllamaModel();
        model2.setId(UUID.randomUUID());
        model2.setName("mistral");

        List<OllamaModel> dbModels = Arrays.asList(model1, model2);

        OllamaApi.Model nativeModel1 = mock(OllamaApi.Model.class);
        when(nativeModel1.name()).thenReturn("llama3");

        OllamaApi.Model nativeModel2 = mock(OllamaApi.Model.class);
        when(nativeModel2.name()).thenReturn("mistral");

        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(Arrays.asList(nativeModel1, nativeModel2));

        when(modelRepository.findAll()).thenReturn(dbModels);
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        List<OllamaModel> result = ollamaService.models();

        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().getName()).isEqualTo("llama3");

        assertThat(result.get(1).getName()).isEqualTo("mistral");

        verify(modelRepository).findAll();
        verify(ollamaApi, times(2)).listModels();
    }
}
