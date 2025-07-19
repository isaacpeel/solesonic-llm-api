package com.solesonic.izzybot.service.ollama;

import com.solesonic.izzybot.exception.ChatException;
import com.solesonic.izzybot.model.ollama.OllamaModel;
import com.solesonic.izzybot.repository.ollama.OllamaModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.ollama.api.OllamaApi;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class OllamaServiceTest {

    @Mock
    private OllamaApi ollamaApi;

    @Mock
    private OllamaModelRepository modelRepository;

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
        ollamaModel.setTools(false);
        ollamaModel.setCreated(ZonedDateTime.now());
        ollamaModel.setUpdated(ZonedDateTime.now());
    }

    @Test
    void testGet() {
        // Arrange
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(ollamaModel));
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);

        // Mock OllamaApi.Model
        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.name()).thenReturn("llama3");
        when(nativeModel.model()).thenReturn("llama3:8b");
        when(nativeModel.size()).thenReturn(8000000000L);

        // Mock OllamaApi.ListModelResponse
        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(List.of(nativeModel));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        // Act
        OllamaModel result = ollamaService.get(modelId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(modelId);
        assertThat(result.getName()).isEqualTo("llama3");
        assertThat(result.getModel()).isEqualTo("llama3:8b");
        assertThat(result.getSize()).isEqualTo(8000000000L);
        verify(modelRepository).findById(modelId);
        verify(modelRepository).save(any(OllamaModel.class));
        verify(ollamaApi).listModels();
    }

    @Test
    void testGetNotFound() {
        // Arrange
        when(modelRepository.findById(modelId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> ollamaService.get(modelId))
                .isInstanceOf(ChatException.class)
                .hasMessage("OLLAMA MODEL NOT FOUND");
        verify(modelRepository).findById(modelId);
    }

    @Test
    void testSave() {
        // Arrange
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);

        // Mock OllamaApi.Model
        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.name()).thenReturn("llama3");
        when(nativeModel.model()).thenReturn("llama3:8b");
        when(nativeModel.size()).thenReturn(8000000000L);

        // Mock OllamaApi.ListModelResponse
        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(List.of(nativeModel));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        // Act
        OllamaModel result = ollamaService.save(ollamaModel);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(modelId);
        assertThat(result.getName()).isEqualTo("llama3");
        assertThat(result.getModel()).isEqualTo("llama3:8b");
        assertThat(result.getSize()).isEqualTo(8000000000L);
        verify(modelRepository).save(ollamaModel);
        verify(ollamaApi).listModels();
    }

    @Test
    void testUpdate() {
        // Arrange
        when(modelRepository.save(any(OllamaModel.class))).thenReturn(ollamaModel);

        // Mock OllamaApi.Model
        OllamaApi.Model nativeModel = mock(OllamaApi.Model.class);
        when(nativeModel.name()).thenReturn("llama3");
        when(nativeModel.model()).thenReturn("llama3:8b");
        when(nativeModel.size()).thenReturn(8000000000L);

        // Mock OllamaApi.ListModelResponse
        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(List.of(nativeModel));
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        // Act
        OllamaModel result = ollamaService.update(modelId, ollamaModel);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(modelId);
        assertThat(result.getName()).isEqualTo("llama3");
        assertThat(result.getModel()).isEqualTo("llama3:8b");
        assertThat(result.getSize()).isEqualTo(8000000000L);
        verify(modelRepository).save(ollamaModel);
        verify(ollamaApi).listModels();
    }

    @Test
    void testModels() {
        // Arrange
        OllamaModel model1 = new OllamaModel();
        model1.setId(UUID.randomUUID());
        model1.setName("llama3");

        OllamaModel model2 = new OllamaModel();
        model2.setId(UUID.randomUUID());
        model2.setName("mistral");

        List<OllamaModel> dbModels = Arrays.asList(model1, model2);

        // Mock OllamaApi.Model
        OllamaApi.Model nativeModel1 = mock(OllamaApi.Model.class);
        when(nativeModel1.name()).thenReturn("llama3");
        when(nativeModel1.model()).thenReturn("llama3:8b");
        when(nativeModel1.size()).thenReturn(8000000000L);

        OllamaApi.Model nativeModel2 = mock(OllamaApi.Model.class);
        when(nativeModel2.name()).thenReturn("mistral");
        when(nativeModel2.model()).thenReturn("mistral:7b");
        when(nativeModel2.size()).thenReturn(7000000000L);

        // Mock OllamaApi.ListModelResponse
        OllamaApi.ListModelResponse listModelResponse = mock(OllamaApi.ListModelResponse.class);
        when(listModelResponse.models()).thenReturn(Arrays.asList(nativeModel1, nativeModel2));

        when(modelRepository.findAll()).thenReturn(dbModels);
        when(ollamaApi.listModels()).thenReturn(listModelResponse);

        // Act
        List<OllamaModel> result = ollamaService.models();

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().getName()).isEqualTo("llama3");
        assertThat(result.getFirst().getModel()).isEqualTo("llama3:8b");
        assertThat(result.getFirst().getSize()).isEqualTo(8000000000L);

        assertThat(result.get(1).getName()).isEqualTo("mistral");
        assertThat(result.get(1).getModel()).isEqualTo("mistral:7b");
        assertThat(result.get(1).getSize()).isEqualTo(7000000000L);

        verify(modelRepository).findAll();
        verify(ollamaApi).listModels();
    }
}
