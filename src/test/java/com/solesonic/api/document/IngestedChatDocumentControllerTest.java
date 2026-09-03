package com.solesonic.api.document;

import com.solesonic.model.document.DocumentSource;
import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.rag.RetrievalScope;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.ingestion.IngestedDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The conversation-scoped collection, and above all that every one of its methods establishes the
 * caller owns the chat before it touches a document.
 * <p>
 * The check answers {@code 404}, not {@code 403} — a chat belonging to someone else must be
 * indistinguishable from one that does not exist, the stance {@code ChatService.get} already takes.
 * That is the difference from {@link IngestedUserDocumentControllerTest}, where a mismatched path
 * {@code userId} is a value the caller supplied about themselves and has nothing to conceal.
 */
@ExtendWith(MockitoExtension.class)
class IngestedChatDocumentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private ChatService chatService;

    @Mock
    private UserRequestContext userRequestContext;

    @InjectMocks
    private IngestedChatDocumentController ingestedChatDocumentController;

    private UUID chatId;
    private UUID documentId;
    private UUID userId;

    @BeforeEach
    void beforeEach() {
        chatId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        userId = UUID.randomUUID();

        lenient().when(userRequestContext.getUserId()).thenReturn(userId);

        mockMvc = MockMvcBuilders.standaloneSetup(ingestedChatDocumentController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private IngestedDocumentSummary summary() {
        return new IngestedDocumentSummary(documentId,
                "notes.pdf",
                "application/pdf",
                1024L,
                DocumentSource.USER,
                RetrievalScope.CHAT,
                chatId,
                DocumentStatus.QUEUED,
                ZonedDateTime.now(),
                ZonedDateTime.now());
    }

    /**
     * No {@code scope} parameter reaches the service from this route either — the collection is the
     * scope, and the owner comes from the request context rather than from anything the client sent.
     */
    @Test
    void uploadQueuesAtChatScopeForThePathChat() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentService.queueForChat(any(), eq(chatId), eq(userId))).thenReturn(summary());

        mockMvc.perform(multipart("/chats/{chatId}/documents", chatId).file(file))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "http://localhost/chats/" + chatId + "/documents/" + documentId))
                .andExpect(jsonPath("$.scope").value("CHAT"))
                .andExpect(jsonPath("$.chatId").value(chatId.toString()));
    }

    @Test
    void listReturnsOnlyThisChatsDocuments() throws Exception {
        when(ingestedDocumentService.listForChat(eq(chatId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/chats/{chatId}/documents", chatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(documentId.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /**
     * A document of another conversation. The repository's {@code where} clause is what makes it
     * absent, and absence is a {@code 404}.
     */
    @Test
    void aDocumentInAnotherChatIsNotFound() throws Exception {
        when(ingestedDocumentService.getForChat(documentId, chatId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/chats/{chatId}/documents/{documentId}", chatId, documentId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesTheDocumentAndNotTheAttachment() throws Exception {
        mockMvc.perform(delete("/chats/{chatId}/documents/{documentId}", chatId, documentId))
                .andExpect(status().isNoContent());

        verify(ingestedDocumentService).deleteForChat(documentId, chatId);
    }

    @Test
    void renameSendsOnlyTheNewName() throws Exception {
        when(ingestedDocumentService.renameForChat(documentId, chatId, "reading.pdf")).thenReturn(summary());

        mockMvc.perform(patch("/chats/{chatId}/documents/{documentId}", chatId, documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"reading.pdf\"}"))
                .andExpect(status().isOk());

        verify(ingestedDocumentService).renameForChat(documentId, chatId, "reading.pdf");
    }

    @Test
    void refreshRequeuesTheDocument() throws Exception {
        when(ingestedDocumentService.refreshForChat(documentId, chatId)).thenReturn(summary());

        mockMvc.perform(post("/chats/{chatId}/documents/{documentId}/refresh", chatId, documentId))
                .andExpect(status().isAccepted());

        verify(ingestedDocumentService).refreshForChat(documentId, chatId);
    }

    /**
     * Every method, not just the reads. A guard covering only some of them would leave every other
     * user's conversation-scoped material readable, writable and deletable by anyone who can guess a
     * chat id.
     */
    @Test
    void everyMethodRefusesAChatTheCallerDoesNotOwn() throws Exception {
        UUID otherChatId = UUID.randomUUID();

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + otherChatId))
                .when(chatService).requireOwned(otherChatId);

        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2});

        mockMvc.perform(multipart("/chats/{chatId}/documents", otherChatId).file(file))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/chats/{chatId}/documents", otherChatId))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/chats/{chatId}/documents/{documentId}", otherChatId, documentId))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/chats/{chatId}/documents/{documentId}", otherChatId, documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"stolen.pdf\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/chats/{chatId}/documents/{documentId}", otherChatId, documentId))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/chats/{chatId}/documents/{documentId}/refresh", otherChatId, documentId))
                .andExpect(status().isNotFound());

        verifyNoInteractions(ingestedDocumentService);
    }
}
