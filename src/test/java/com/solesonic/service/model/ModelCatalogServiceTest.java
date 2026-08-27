package com.solesonic.service.model;

import com.solesonic.exception.ChatException;
import com.solesonic.model.llm.LlmModel;
import com.solesonic.repository.llm.LlmModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCatalogServiceTest {

    @Mock
    private LlmModelRepository modelRepository;

    @InjectMocks
    private ModelCatalogService modelCatalogService;

    private UUID modelId;
    private LlmModel llmModel;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();

        llmModel = new LlmModel();
        llmModel.setId(modelId);
        llmModel.setName("llama3");
        llmModel.setCreated(ZonedDateTime.now());
        llmModel.setUpdated(ZonedDateTime.now());
    }

    @Test
    void returnsTheStoredCatalogUnenriched() {
        LlmModel second = new LlmModel();
        second.setId(UUID.randomUUID());
        second.setName("mistral");

        when(modelRepository.findAll()).thenReturn(List.of(llmModel, second));

        List<LlmModel> models = modelCatalogService.models();

        assertThat(models).hasSize(2);
        assertThat(models.getFirst().getName()).isEqualTo("llama3");
        assertThat(models.get(1).getName()).isEqualTo("mistral");
    }

    @Test
    void getReturnsTheStoredModel() {
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(llmModel));

        LlmModel found = modelCatalogService.get(modelId);

        assertThat(found.getId()).isEqualTo(modelId);
        assertThat(found.getName()).isEqualTo("llama3");
    }

    @Test
    void getThrowsWhenTheModelIsNotInTheCatalog() {
        when(modelRepository.findById(modelId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> modelCatalogService.get(modelId))
                .isInstanceOf(ChatException.class)
                .hasMessage("MODEL NOT FOUND");
    }

    @Test
    void saveStampsBothTimestamps() {
        when(modelRepository.save(any(LlmModel.class))).thenReturn(llmModel);

        LlmModel toSave = new LlmModel();
        toSave.setName("llama3");

        LlmModel saved = modelCatalogService.save(toSave);

        assertThat(saved).isSameAs(llmModel);

        ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
        verify(modelRepository).save(captor.capture());

        assertThat(captor.getValue().getCreated()).isNotNull();
        assertThat(captor.getValue().getUpdated()).isNotNull();
    }

    /**
     * The id comes from the path, not the body — otherwise a client could rename one row by
     * updating another.
     */
    @Test
    void updateTakesTheIdFromThePathAndOnlyTouchesUpdated() {
        when(modelRepository.save(any(LlmModel.class))).thenReturn(llmModel);

        LlmModel submitted = new LlmModel();
        submitted.setName("llama3");

        modelCatalogService.update(modelId, submitted);

        ArgumentCaptor<LlmModel> captor = ArgumentCaptor.forClass(LlmModel.class);
        verify(modelRepository).save(captor.capture());

        assertThat(captor.getValue().getId()).isEqualTo(modelId);
        assertThat(captor.getValue().getUpdated()).isNotNull();
        assertThat(captor.getValue().getCreated()).isNull();
    }

    @Test
    void deleteRemovesTheRow() {
        modelCatalogService.delete(modelId);

        verify(modelRepository).deleteById(modelId);
    }
}
