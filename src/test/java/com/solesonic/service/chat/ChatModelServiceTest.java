package com.solesonic.service.chat;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIIoException;
import com.openai.models.models.Model;
import com.openai.models.models.ModelListPage;
import com.openai.services.blocking.ModelService;
import com.solesonic.exception.chat.ChatModelException;
import com.solesonic.model.chat.model.AvailableModel;
import com.solesonic.model.chat.model.ChatModelErrorCode;
import com.solesonic.model.chat.model.ChatModelOptions;
import com.solesonic.model.chat.model.ChatModelState;
import com.solesonic.service.a2a.A2AStickyAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatModelServiceTest {

    private static final String DEFAULT_MODEL = "qwen3-8b";

    @Mock
    private OpenAIClient openAiClient;
    @Mock
    private ModelService modelService;
    @Mock
    private ModelListPage modelListPage;
    @Mock
    private ChatModelSelection chatModelSelection;
    @Mock
    private A2AStickyAgentService a2aStickyAgentService;

    private UUID chatId;

    private ChatModelService chatModelService;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();

        chatModelService = new ChatModelService(openAiClient, chatModelSelection, a2aStickyAgentService, DEFAULT_MODEL);

        lenient().when(a2aStickyAgentService.getActiveAgent(any())).thenReturn(Mono.just(Optional.empty()));
        lenient().when(chatModelSelection.get(any())).thenReturn(Mono.empty());
        lenient().when(chatModelSelection.set(any(), anyString())).thenReturn(Mono.empty());
        lenient().when(chatModelSelection.clear(any())).thenReturn(Mono.empty());
    }

    @Test
    void optionsListTheEndpointsModelsInItsOwnOrderAndSpelling() {
        stubAvailableModels("openai/gpt-4o", "qwen3:8b");

        ChatModelOptions options = chatModelService.options();

        assertThat(options.models()).containsExactly(
                new AvailableModel("openai/gpt-4o", "owner"),
                new AvailableModel("qwen3:8b", "owner"));
        assertThat(options.defaultModel()).isEqualTo(DEFAULT_MODEL);
        assertThat(options.selectedModel()).isNull();
        assertThat(options.activeAgent()).isNull();
    }

    @Test
    void optionsForAChatReportItsSelectionAndAgent() {
        stubAvailableModels("openai/gpt-4o");
        when(chatModelSelection.get(chatId)).thenReturn(Mono.just("openai/gpt-4o"));
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.just(Optional.of("jira")));

        ChatModelOptions options = chatModelService.options(chatId);

        assertThat(options.selectedModel()).isEqualTo("openai/gpt-4o");
        assertThat(options.activeAgent()).isEqualTo("jira");
        assertThat(options.defaultModel()).isEqualTo(DEFAULT_MODEL);
    }

    @Test
    void optionsAreUnavailableWhenTheListCannotBeFetched() {
        when(openAiClient.models()).thenReturn(modelService);
        when(modelService.list()).thenThrow(new OpenAIIoException("connection refused"));

        assertThatThrownBy(() -> chatModelService.options())
                .isInstanceOfSatisfying(ChatModelException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ChatModelErrorCode.MODEL_LIST_UNAVAILABLE));
    }

    /**
     * The endpoint's spelling is the one a completion request has to carry, whatever case the user
     * typed, and {@code /} and {@code :} are ordinary characters in a model id.
     */
    @Test
    void selectStoresTheEndpointsSpellingOfACaseInsensitiveMatch() {
        stubAvailableModels("openai/gpt-4o", "qwen3:8b");
        when(chatModelSelection.get(chatId)).thenReturn(Mono.just("qwen3:8b"));

        ChatModelState state = chatModelService.select(chatId, "  QWEN3:8B ");

        verify(chatModelSelection).set(chatId, "qwen3:8b");
        assertThat(state.selectedModel()).isEqualTo("qwen3:8b");
    }

    @Test
    void selectStoresNothingForAnUnknownModel() {
        stubAvailableModels("openai/gpt-4o");

        assertThatThrownBy(() -> chatModelService.select(chatId, "gpt-5"))
                .isInstanceOfSatisfying(ChatModelException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ChatModelErrorCode.UNKNOWN_MODEL));

        verify(chatModelSelection, never()).set(any(), anyString());
    }

    @Test
    void selectStoresNothingForABlankName() {
        assertThatThrownBy(() -> chatModelService.select(chatId, " "))
                .isInstanceOfSatisfying(ChatModelException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ChatModelErrorCode.UNKNOWN_MODEL));

        verifyNoInteractions(openAiClient);
        verify(chatModelSelection, never()).set(any(), anyString());
    }

    @Test
    void selectStoresNothingWhenTheListCannotBeFetched() {
        when(openAiClient.models()).thenReturn(modelService);
        when(modelService.list()).thenThrow(new OpenAIIoException("connection refused"));

        assertThatThrownBy(() -> chatModelService.select(chatId, "openai/gpt-4o"))
                .isInstanceOfSatisfying(ChatModelException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ChatModelErrorCode.MODEL_LIST_UNAVAILABLE));

        verify(chatModelSelection, never()).set(any(), anyString());
    }

    @Test
    void resetClearsTheSelection() {
        chatModelService.reset(chatId);

        verify(chatModelSelection).clear(chatId);
    }

    /**
     * A selection that cannot be read is reported as absent, which is what {@code PromptService}
     * falls back to in the same case.
     */
    @Test
    void stateReportsNoSelectionWhenRedisCannotBeRead() {
        when(chatModelSelection.get(chatId)).thenReturn(Mono.error(new IllegalStateException("redis down")));
        when(a2aStickyAgentService.getActiveAgent(chatId)).thenReturn(Mono.error(new IllegalStateException("redis down")));

        ChatModelState state = chatModelService.state(chatId);

        assertThat(state).isEqualTo(new ChatModelState(DEFAULT_MODEL, null, null));
    }

    private void stubAvailableModels(String... modelIds) {
        List<Model> models = Arrays.stream(modelIds)
                .map(modelId -> Model.builder().id(modelId).created(0).ownedBy("owner").build())
                .toList();

        when(openAiClient.models()).thenReturn(modelService);
        when(modelService.list()).thenReturn(modelListPage);
        when(modelListPage.data()).thenReturn(models);
    }
}
