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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * The chat document collection, which is the caller's rather than the conversation's.
 * <p>
 * The {@code rag-admin} gate on {@code promote/global} is an annotation, which a standalone
 * {@code MockMvc} does not apply — {@link IngestedDocumentMethodSecurityTest} is where enforcement
 * is covered. What is covered here is routing, the filters, and that the caller is taken from the
 * request context rather than from the path.
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

    private UUID documentId;
    private UUID chatId;
    private UUID userId;

    @BeforeEach
    void beforeEach() {
        documentId = UUID.randomUUID();
        chatId = UUID.randomUUID();
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
                DocumentSource.CHAT,
                RetrievalScope.CHAT,
                chatId,
                List.of("chat:" + chatId),
                "Quarterly planning",
                DocumentStatus.COMPLETED,
                ZonedDateTime.now(),
                ZonedDateTime.now());
    }

    /**
     * The one operation that names a conversation, and therefore the one that has to check it
     * belongs to the caller. Everything else addresses a document the caller already manages.
     */
    @Test
    void uploadRequiresTheConversationToBeOwned() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2});

        when(ingestedDocumentService.queueForChat(any(), eq(chatId), eq(userId))).thenReturn(summary());

        mockMvc.perform(multipart("/chats/documents").file(file).param("chatId", chatId.toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/chats/documents/" + documentId))
                .andExpect(jsonPath("$.id").value(documentId.toString()));

        verify(chatService).requireOwned(chatId);
    }

    @Test
    void uploadToSomeoneElsesConversationIsNotFound() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", new byte[]{1, 2});

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"))
                .when(chatService).requireOwned(chatId);

        mockMvc.perform(multipart("/chats/documents").file(file).param("chatId", chatId.toString()))
                .andExpect(status().isNotFound());

        verify(ingestedDocumentService, never()).queueForChat(any(), any(), any());
    }

    /**
     * With no {@code chatId}, the listing spans every conversation the caller has uploaded to — the
     * question the old {@code /chats/{chatId}/documents} route could not ask at all.
     */
    @Test
    void listsAcrossEveryConversationByDefault() throws Exception {
        when(ingestedDocumentService.listChatDocuments(eq(userId), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/chats/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(documentId.toString()))
                .andExpect(jsonPath("$.content[0].chatName").value("Quarterly planning"))
                .andExpect(jsonPath("$.content[0].entitlements[0]").value("chat:" + chatId));

        verify(chatService, never()).requireOwned(any());
    }

    /**
     * A conversation belonging to someone else produces an empty page rather than a 403 — the
     * {@code MANAGE} join in the query is the authorization, so there is nothing to refuse.
     */
    @Test
    void narrowsToOneConversationWhenAsked() throws Exception {
        when(ingestedDocumentService.listChatDocuments(eq(userId), eq(chatId), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/chats/documents").param("chatId", chatId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(documentId.toString()));
    }

    /**
     * A {@code FAILED} chat document is invisible in every other surface — nothing lists it, and the
     * user is never told their attachment did not index.
     */
    @Test
    void filtersByStatusSoAFailedDocumentCanBeFound() throws Exception {
        when(ingestedDocumentService.listChatDocuments(
                eq(userId), isNull(), eq(DocumentStatus.FAILED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/chats/documents").param("status", "FAILED"))
                .andExpect(status().isOk());

        verify(ingestedDocumentService)
                .listChatDocuments(eq(userId), isNull(), eq(DocumentStatus.FAILED), any(Pageable.class));
    }

    @Test
    void getsOneDocumentByTheCallersManageGrant() throws Exception {
        when(ingestedDocumentService.getChatDocument(documentId, userId)).thenReturn(summary());

        mockMvc.perform(get("/chats/documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileData").doesNotExist())
                .andExpect(jsonPath("$.fileSizeBytes").value(1024));
    }

    @Test
    void renameSendsOnlyTheNewName() throws Exception {
        when(ingestedDocumentService.renameChatDocument(documentId, userId, "onboarding.pdf"))
                .thenReturn(summary());

        mockMvc.perform(patch("/chats/documents/{documentId}", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"onboarding.pdf\"}"))
                .andExpect(status().isOk());

        verify(ingestedDocumentService).renameChatDocument(documentId, userId, "onboarding.pdf");
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/chats/documents/{documentId}", documentId))
                .andExpect(status().isNoContent());

        verify(ingestedDocumentService).deleteChatDocument(documentId, userId);
    }

    @Test
    void refreshIsAccepted() throws Exception {
        when(ingestedDocumentService.refreshChatDocument(documentId, userId)).thenReturn(summary());

        mockMvc.perform(post("/chats/documents/{documentId}/refresh", documentId))
                .andExpect(status().isAccepted());
    }

    @Test
    void promoteToUserMovesTheDocumentIntoTheCallersLibrary() throws Exception {
        when(ingestedDocumentService.promoteToUser(documentId, userId)).thenReturn(summary());

        mockMvc.perform(post("/chats/documents/{documentId}/promote/user", documentId))
                .andExpect(status().isOk());

        verify(ingestedDocumentService).promoteToUser(documentId, userId);
    }

    @Test
    void promoteToGlobalMovesTheDocumentIntoTheSharedCorpus() throws Exception {
        when(ingestedDocumentService.promoteToGlobal(documentId, userId)).thenReturn(summary());

        mockMvc.perform(post("/chats/documents/{documentId}/promote/global", documentId))
                .andExpect(status().isOk());

        verify(ingestedDocumentService).promoteToGlobal(documentId, userId);
    }

    /**
     * A promotion mid-ingest would race the chunk stamping it is trying to rewrite.
     */
    @Test
    void promotingADocumentThatIsStillIngestingConflicts() throws Exception {
        when(ingestedDocumentService.promoteToUser(documentId, userId))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Document is IN_PROGRESS"));

        mockMvc.perform(post("/chats/documents/{documentId}/promote/user", documentId))
                .andExpect(status().isConflict());
    }

    /**
     * Promotion cannot de-duplicate the way an upload does — it already has a different row, and
     * silently merging two documents would discard one.
     */
    @Test
    void promotingOverAnExistingGlobalNameConflicts() throws Exception {
        when(ingestedDocumentService.promoteToGlobal(documentId, userId))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "A shared document named 'notes.pdf' already exists"));

        mockMvc.perform(post("/chats/documents/{documentId}/promote/global", documentId))
                .andExpect(status().isConflict());
    }

    @Test
    void aDocumentTheCallerDoesNotManageIsNotFound() throws Exception {
        when(ingestedDocumentService.getChatDocument(documentId, userId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No such ingested document"));

        mockMvc.perform(get("/chats/documents/{documentId}", documentId))
                .andExpect(status().isNotFound());
    }
}
