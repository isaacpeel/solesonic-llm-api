package com.solesonic.api.chat;

import com.solesonic.exception.chat.ChatModelException;
import com.solesonic.exception.handler.AttachmentExceptionHandler;
import com.solesonic.exception.handler.ChatModelExceptionHandler;
import com.solesonic.exception.handler.ExceptionService;
import com.solesonic.exception.handler.GeneralExceptionHandler;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.chat.model.AvailableModel;
import com.solesonic.model.chat.model.ChatModelErrorCode;
import com.solesonic.model.chat.model.ChatModelOptions;
import com.solesonic.model.chat.model.ChatModelState;
import com.solesonic.service.chat.ChatModelService;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.security.ResourceOwnershipService;
import com.solesonic.service.security.SecurityEventLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the model endpoints' status codes, and that they sit beside {@link ChatController}'s
 * {@code /chats/{chatId}} routes without either one capturing the other's requests.
 */
@ExtendWith(MockitoExtension.class)
class ChatModelControllerTest {

    private static final String DEFAULT_MODEL = "qwen3-8b";

    @Mock
    private ChatModelService chatModelService;

    @Mock
    private ChatService chatService;

    @Mock
    private ResourceOwnershipService resourceOwnershipService;

    @Mock
    private ExceptionService exceptionService;

    @Mock
    private SecurityEventLogger securityEventLogger;

    private MockMvc mockMvc;

    private UUID chatId;

    @BeforeEach
    void setUp() {
        chatId = UUID.randomUUID();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatModelController(chatModelService, chatService),
                        new ChatController(chatService, resourceOwnershipService))
                .setControllerAdvice(new ChatModelExceptionHandler(), new AttachmentExceptionHandler(),
                        new GeneralExceptionHandler(exceptionService, securityEventLogger))
                .build();
    }

    /**
     * The request that once reached {@code GET /chats/{chatId}} and failed converting "models" to a
     * UUID. A literal segment outranks a template, so it lands here.
     */
    @Test
    void listsModelsRatherThanLookingUpAChatCalledModels() throws Exception {
        when(chatModelService.options()).thenReturn(new ChatModelOptions(
                List.of(new AvailableModel("openai/gpt-4o", "openai")), DEFAULT_MODEL, null, null));

        mockMvc.perform(get("/chats/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].id").value("openai/gpt-4o"))
                .andExpect(jsonPath("$.models[0].ownedBy").value("openai"))
                .andExpect(jsonPath("$.defaultModel").value(DEFAULT_MODEL))
                .andExpect(jsonPath("$.selectedModel").doesNotExist());

        verify(chatService, never()).get(any());
        verify(chatService, never()).requireOwned(any());
    }

    @Test
    void stillLooksUpAChatById() throws Exception {
        Chat chat = new Chat();
        chat.setId(chatId);

        when(chatService.get(chatId)).thenReturn(chat);

        mockMvc.perform(get("/chats/{chatId}", chatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chatId.toString()));

        verify(chatModelService, never()).options();
    }

    @Test
    void listsModelsForAnOwnedChat() throws Exception {
        when(chatModelService.options(chatId)).thenReturn(new ChatModelOptions(
                List.of(new AvailableModel("openai/gpt-4o", "openai")), DEFAULT_MODEL, "openai/gpt-4o", "jira"));

        mockMvc.perform(get("/chats/models").param("chatId", chatId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedModel").value("openai/gpt-4o"))
                .andExpect(jsonPath("$.activeAgent").value("jira"));

        verify(chatService).requireOwned(chatId);
    }

    @Test
    void refusesToListModelsForAnotherUsersChat() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found")).when(chatService).requireOwned(chatId);

        mockMvc.perform(get("/chats/models").param("chatId", chatId.toString()))
                .andExpect(status().isNotFound());

        verify(chatModelService, never()).options(any());
    }

    @Test
    void answersServiceUnavailableWhenTheListCannotBeFetched() throws Exception {
        when(chatModelService.options()).thenThrow(
                new ChatModelException(ChatModelErrorCode.MODEL_LIST_UNAVAILABLE, "The model list could not be retrieved."));

        mockMvc.perform(get("/chats/models"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_LIST_UNAVAILABLE"));
    }

    @Test
    void selectsAModelForAnOwnedChat() throws Exception {
        when(chatModelService.select(chatId, "OpenAI/GPT-4o"))
                .thenReturn(new ChatModelState(DEFAULT_MODEL, "openai/gpt-4o", null));

        mockMvc.perform(put("/chats/{chatId}/model", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"OpenAI/GPT-4o\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedModel").value("openai/gpt-4o"))
                .andExpect(jsonPath("$.defaultModel").value(DEFAULT_MODEL))
                .andExpect(jsonPath("$.models").doesNotExist());

        verify(chatService).requireOwned(chatId);
    }

    @Test
    void refusesAnUnknownModel() throws Exception {
        when(chatModelService.select(chatId, "gpt-5"))
                .thenThrow(new ChatModelException(ChatModelErrorCode.UNKNOWN_MODEL, "The model gpt-5 is not offered."));

        mockMvc.perform(put("/chats/{chatId}/model", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-5\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_MODEL"));
    }

    @Test
    void answersServiceUnavailableWhenASelectionCannotBeValidated() throws Exception {
        when(chatModelService.select(chatId, "openai/gpt-4o")).thenThrow(
                new ChatModelException(ChatModelErrorCode.MODEL_LIST_UNAVAILABLE, "The model list could not be retrieved."));

        mockMvc.perform(put("/chats/{chatId}/model", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/gpt-4o\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_LIST_UNAVAILABLE"));
    }

    @Test
    void refusesToSelectAModelForAnotherUsersChat() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found")).when(chatService).requireOwned(chatId);

        mockMvc.perform(put("/chats/{chatId}/model", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/gpt-4o\"}"))
                .andExpect(status().isNotFound());

        verify(chatModelService, never()).select(any(), any());
    }

    @Test
    void resetIsIdempotent() throws Exception {
        mockMvc.perform(delete("/chats/{chatId}/model", chatId))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/chats/{chatId}/model", chatId))
                .andExpect(status().isNoContent());

        verify(chatModelService, times(2)).reset(chatId);
        verify(chatService, never()).delete(any());
    }

    @Test
    void refusesToResetAnotherUsersChat() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found")).when(chatService).requireOwned(chatId);

        mockMvc.perform(delete("/chats/{chatId}/model", chatId))
                .andExpect(status().isNotFound());

        verify(chatModelService, never()).reset(any());
    }

    /**
     * A word where a chat id belongs is the caller's mistake: a {@code 400}, never the {@code 500} the
     * catch-all would make of the conversion failure.
     */
    @Test
    void answersBadRequestForAChatIdThatIsNotAUuid() throws Exception {
        mockMvc.perform(put("/chats/{chatId}/model", "not-a-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"openai/gpt-4o\"}"))
                .andExpect(status().isBadRequest());

        verify(chatModelService, never()).select(any(), any());
    }
}
