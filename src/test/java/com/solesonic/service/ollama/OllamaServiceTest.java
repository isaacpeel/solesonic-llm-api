package com.solesonic.service.ollama;

import com.solesonic.exception.ChatException;
import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.repository.ollama.OllamaModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.ollama.api.OllamaApi;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OllamaServiceTest {

    @Mock
    private OllamaApi ollamaApi;

    @Mock
    private OllamaModelRepository modelRepository;

    @Mock
    private OllamaModelCacheService ollamaModelCacheService;

    private OllamaService ollamaService;

    private UUID modelId;
    private OllamaModel ollamaModel;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = new JsonMapper();
        ollamaService = new OllamaService(ollamaApi, modelRepository, ollamaModelCacheService, jsonMapper);
        modelId = UUID.randomUUID();

        ollamaModel = new OllamaModel();
        ollamaModel.setId(modelId);
        ollamaModel.setName("llama3");
        ollamaModel.setCreated(ZonedDateTime.now());
        ollamaModel.setUpdated(ZonedDateTime.now());
    }

    @SuppressWarnings("unchecked")
    @Test
    void testGet() {
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(ollamaModel));
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);
        when(ollamaModelCacheService.getModelDetails("llama3")).thenReturn(Optional.empty());
        when(ollamaModelCacheService.getShowModel("llama3")).thenReturn(Optional.empty());

        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.model()).thenReturn("llama3");

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
        verify(ollamaApi).showModel(any());
        verify(ollamaModelCacheService).putModelDetails(eq("llama3"), any(Map.class));
        verify(ollamaModelCacheService).putShowModel(eq("llama3"), any(Map.class));
    }

    @Test
    void testGetUsesCache() {
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(ollamaModel));
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);
        when(ollamaModelCacheService.getModelDetails("llama3")).thenReturn(Optional.of(Map.of("model", "llama3")));
        when(ollamaModelCacheService.getShowModel("llama3")).thenReturn(Optional.of(Map.of("modelfile", "FROM llama3")));

        OllamaModel result = ollamaService.get(modelId);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("llama3");
        verify(ollamaApi, never()).listModels();
        verify(ollamaApi, never()).showModel(any());
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
        when(ollamaModelCacheService.getModelDetails("llama3")).thenReturn(Optional.empty());
        when(ollamaModelCacheService.getShowModel("llama3")).thenReturn(Optional.empty());

        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.model()).thenReturn("llama3");

        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(List.of(nativeModel));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        OllamaApi.ShowModelResponse showModelResponse = mock(OllamaApi.ShowModelResponse.class);
        when(ollamaApi.showModel(any())).thenReturn(showModelResponse);

        OllamaModel result = ollamaService.save(ollamaModel);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(modelId);
        assertThat(result.getName()).isEqualTo("llama3");
        verify(modelRepository).save(ollamaModel);
        verify(ollamaModelCacheService).evictModel("llama3");
        verify(ollamaApi).listModels();
        verify(ollamaApi).showModel(any());
    }

    @Test
    void testUpdate() {
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);
        when(ollamaModelCacheService.getModelDetails("llama3")).thenReturn(Optional.empty());
        when(ollamaModelCacheService.getShowModel("llama3")).thenReturn(Optional.empty());

        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.model()).thenReturn("llama3");

        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(List.of(nativeModel));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        OllamaApi.ShowModelResponse showModelResponse = mock(OllamaApi.ShowModelResponse.class);
        when(ollamaApi.showModel(any())).thenReturn(showModelResponse);

        OllamaModel result = ollamaService.update(modelId, ollamaModel);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(modelId);
        assertThat(result.getName()).isEqualTo("llama3");
        verify(modelRepository).save(ollamaModel);
        verify(ollamaModelCacheService).evictModel("llama3");
        verify(ollamaApi).listModels();
        verify(ollamaApi).showModel(any());
    }

    @Test
    void testModels() {
        OllamaModel model1 = new OllamaModel();
        model1.setId(UUID.randomUUID());
        model1.setName("llama3");

        OllamaModel model2 = new OllamaModel();
        model2.setId(UUID.randomUUID());
        model2.setName("mistral");

        when(modelRepository.findAll()).thenReturn(Arrays.asList(model1, model2));
        when(ollamaModelCacheService.getModelDetails(any())).thenReturn(Optional.empty());
        when(ollamaModelCacheService.getShowModel(any())).thenReturn(Optional.empty());

        OllamaApi.Model nativeModel1 = mock(OllamaApi.Model.class);
        when(nativeModel1.model()).thenReturn("llama3");

        OllamaApi.Model nativeModel2 = mock(OllamaApi.Model.class);
        when(nativeModel2.model()).thenReturn("mistral");

        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(Arrays.asList(nativeModel1, nativeModel2));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        OllamaApi.ShowModelResponse showModelResponse = mock(OllamaApi.ShowModelResponse.class);
        when(ollamaApi.showModel(any())).thenReturn(showModelResponse);

        List<OllamaModel> result = ollamaService.models();

        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().getName()).isEqualTo("llama3");
        assertThat(result.get(1).getName()).isEqualTo("mistral");
        verify(modelRepository).findAll();
        verify(ollamaApi, times(2)).listModels();
        verify(ollamaApi, times(2)).showModel(any());
    }

    @Test
    void testModelsUsesCache() {
        OllamaModel model1 = new OllamaModel();
        model1.setId(UUID.randomUUID());
        model1.setName("llama3");

        when(modelRepository.findAll()).thenReturn(List.of(model1));
        when(ollamaModelCacheService.getModelDetails("llama3")).thenReturn(Optional.of(Map.of("model", "llama3")));
        when(ollamaModelCacheService.getShowModel("llama3")).thenReturn(Optional.of(Map.of("modelfile", "FROM llama3")));

        List<OllamaModel> result = ollamaService.models();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getName()).isEqualTo("llama3");
        verify(ollamaApi, never()).listModels();
        verify(ollamaApi, never()).showModel(any());
    }
}
