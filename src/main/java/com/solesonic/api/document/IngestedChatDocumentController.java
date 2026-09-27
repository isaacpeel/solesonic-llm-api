package com.solesonic.api.document;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.ingestion.IngestedDocumentUpdateRequest;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.chat.ChatService;
import com.solesonic.service.ingestion.IngestedDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Every document the caller has ever uploaded to any conversation.
 * <p>
 * Mounted at {@code /chats/documents} rather than {@code /chats/{chatId}/documents}, which is the
 * whole point: a chat document is owned by the person who uploaded it, not by the conversation, so
 * the collection is theirs and the conversation is a filter on it. The old path could only answer
 * "what is in this one chat", and could not answer "where did I put that file" at all.
 * <p>
 * Authorization is the {@code MANAGE} grant, applied inside every query — there is no path on which
 * it can be skipped, and a document belonging to someone else is a {@code 404} rather than a
 * {@code 403}, matching how {@code ChatService.get} answers. The caller is taken from
 * {@link UserRequestContext}, which reads the JWT subject and never a path segment, so there is no
 * {@code {userId}} here that could disagree with the token.
 * <p>
 * {@code POST} is the one place a chat id is supplied and the one place
 * {@code ChatService.requireOwned} is needed: adding to a conversation is the only operation whose
 * target is the conversation rather than the document.
 */
@RestController
@RequestMapping("/chats/documents")
public class IngestedChatDocumentController {
    private static final Logger log = LoggerFactory.getLogger(IngestedChatDocumentController.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final IngestedDocumentService ingestedDocumentService;
    private final ChatService chatService;
    private final UserRequestContext userRequestContext;

    public IngestedChatDocumentController(IngestedDocumentService ingestedDocumentService,
                                          ChatService chatService,
                                          UserRequestContext userRequestContext) {
        this.ingestedDocumentService = ingestedDocumentService;
        this.chatService = chatService;
        this.userRequestContext = userRequestContext;
    }

    /**
     * Uploads a document straight into a conversation.
     * <p>
     * The chat id is a request parameter rather than a path segment because this collection is the
     * caller's, not the conversation's. It is checked against the caller here — the one operation
     * that needs to, since every other one addresses a document the caller already manages.
     */
    @PostMapping
    public ResponseEntity<IngestedDocumentSummary> upload(@RequestParam UUID chatId,
                                                          @RequestParam MultipartFile file) {
        chatService.requireOwned(chatId);

        log.info("Queuing a document for chat {}", chatId);

        IngestedDocumentSummary summary =
                ingestedDocumentService.queueForChat(file, chatId, userRequestContext.getUserId());

        return ResponseEntity.created(location(summary.id())).body(summary);
    }

    /**
     * @param chatId null lists every conversation's documents
     * @param status null lists every status. {@code FAILED} is the reason this filter exists — a
     *               document that did not index is invisible in every other surface
     */
    @GetMapping
    public ResponseEntity<PagedModel<IngestedDocumentSummary>> list(
            @RequestParam(required = false) UUID chatId,
            @RequestParam(required = false) DocumentStatus status,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        Pageable documentPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Listing chat documents page {} size {} chat {} status {}",
                documentPage.getPageNumber(), documentPage.getPageSize(), chatId, status);

        Page<IngestedDocumentSummary> summaries = ingestedDocumentService.listChatDocuments(
                userRequestContext.getUserId(), chatId, status, documentPage);

        return ResponseEntity.ok(new PagedModel<>(summaries));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<IngestedDocumentSummary> get(@PathVariable UUID documentId) {
        log.info("Getting chat document {}", documentId);

        return ResponseEntity.ok(
                ingestedDocumentService.getChatDocument(documentId, userRequestContext.getUserId()));
    }

    @PatchMapping("/{documentId}")
    public ResponseEntity<IngestedDocumentSummary> rename(@PathVariable UUID documentId,
                                                          @RequestBody IngestedDocumentUpdateRequest updateRequest) {
        log.info("Renaming chat document {}", documentId);

        return ResponseEntity.ok(ingestedDocumentService.renameChatDocument(
                documentId, userRequestContext.getUserId(), updateRequest.fileName()));
    }

    /**
     * Removes the document, its chunks and its status history. The {@code chat_attachment} row it
     * arrived on is deliberately left alone — that is {@code DELETE /attachments/{attachmentId}},
     * and deleting it here would rewrite the conversation's history rather than stop the document
     * being retrieved.
     */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        log.info("Deleting chat document {}", documentId);

        ingestedDocumentService.deleteChatDocument(documentId, userRequestContext.getUserId());

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{documentId}/refresh")
    public ResponseEntity<IngestedDocumentSummary> refresh(@PathVariable UUID documentId) {
        log.info("Refreshing chat document {}", documentId);

        return ResponseEntity.accepted().body(
                ingestedDocumentService.refreshChatDocument(documentId, userRequestContext.getUserId()));
    }

    /**
     * Moves the document out of the conversation and into the caller's own library, where it is
     * retrievable in every one of their chats.
     * <p>
     * It survives the origin conversation being deleted: promotion copies the bytes off the
     * attachment and clears the chat provenance from both the row and its chunks, so the teardown
     * that follows a deleted chat no longer reaches it.
     */
    @PostMapping("/{documentId}/promote/user")
    public ResponseEntity<IngestedDocumentSummary> promoteToUser(@PathVariable UUID documentId) {
        log.info("Promoting chat document {} to the caller's library", documentId);

        return ResponseEntity.ok(
                ingestedDocumentService.promoteToUser(documentId, userRequestContext.getUserId()));
    }

    /**
     * Moves the document into the shared corpus, where everyone can retrieve it.
     * <p>
     * A separate endpoint from {@code promote/user} rather than one taking a target, because
     * {@code @PreAuthorize} cannot cleanly express "admin only when the body says GLOBAL", and a
     * runtime role branch inside a service is the shape this codebase avoids — cf. the dead
     * {@code TokenBrokerAuthorizationFilter} whose checks never run. It also keeps the audience out
     * of request parameters, consistent with both other document collections.
     */
    @PreAuthorize("hasRole('rag-admin')")
    @PostMapping("/{documentId}/promote/global")
    public ResponseEntity<IngestedDocumentSummary> promoteToGlobal(@PathVariable UUID documentId) {
        log.info("Promoting chat document {} to the shared corpus", documentId);

        return ResponseEntity.ok(
                ingestedDocumentService.promoteToGlobal(documentId, userRequestContext.getUserId()));
    }

    private static URI location(UUID documentId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/chats/documents/{documentId}")
                .buildAndExpand(documentId)
                .toUri();
    }
}
