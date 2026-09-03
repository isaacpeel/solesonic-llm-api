package com.solesonic.api.document;

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
 * One conversation's own documents: retrievable while answering in that chat and nowhere else.
 * <p>
 * The third collection, and the one whose rows have two origins. A document reaches a chat either by
 * being attached to a message — {@code ChatDocumentIngestionService} indexes it inline on the turn,
 * against a row {@code IngestedDocumentService.beginChatIngestion} opens — or by being uploaded here,
 * to the conversation rather than to a turn of it. Both are {@code CHAT} scoped rows and both are
 * listed, renamed, refreshed and deleted through these methods; what tells them apart is
 * {@code documentSource}, {@code CHAT} for the first and {@code USER} for the second.
 * <p>
 * The scope is the collection, as it is for the other two: nothing here takes a {@code scope}
 * parameter and there is no request shape that can create a document at another scope.
 * <p>
 * Ownership is the conversation's, checked at the top of every method by
 * {@link ChatService#requireOwned(UUID)} — which answers {@code 404} rather than {@code 403} for a
 * chat belonging to someone else, exactly as {@code GET /chats/{chatId}} does. There is no
 * {@code {userId}} segment: a chat already has exactly one owner, and a second id naming the same
 * person would be a value with nothing to check it against. That makes the {@code 404} here mean two
 * things at once, on purpose — no such chat of yours, and no such document of that chat.
 * <p>
 * There is no {@code /uri} sibling. The other two collections have one because a shared or personal
 * corpus is something a user curates over time; a conversation's documents are the ones being
 * discussed in it, and a URI worth keeping past the conversation belongs at {@code USER} scope.
 */
@RestController
@RequestMapping("/chats/{chatId}/documents")
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
     * Adds a document to the conversation without sending a message.
     * <p>
     * Queued rather than ingested inline, which is the one behavioural difference from attaching the
     * same file to a turn: nobody is waiting on a response, so the extraction and embedding go
     * through {@code DocumentIngestionSchedulingTask} like every other queued document, and the
     * document becomes retrievable once its status reaches {@code COMPLETED}.
     * <p>
     * The owner recorded on the row is the caller rather than the chat's owner. They are the same
     * person — {@link ChatService#requireOwned(UUID)} has just established it — and taking it from
     * {@link UserRequestContext} keeps the value coming from the JWT subject rather than from a row
     * this request could not have written.
     */
    @PostMapping
    public ResponseEntity<IngestedDocumentSummary> upload(@PathVariable UUID chatId,
                                                          @RequestParam MultipartFile file) {
        chatService.requireOwned(chatId);

        log.info("Queuing a document for chat {}", chatId);
        IngestedDocumentSummary summary =
                ingestedDocumentService.queueForChat(file, chatId, userRequestContext.getUserId());

        return ResponseEntity.created(location(chatId, summary.id())).body(summary);
    }

    @GetMapping
    public ResponseEntity<PagedModel<IngestedDocumentSummary>> list(
            @PathVariable UUID chatId,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        chatService.requireOwned(chatId);

        // The window only, for the reason IngestedGlobalDocumentController.list gives.
        Pageable documentPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Listing documents for chat {} page {} size {}",
                chatId, documentPage.getPageNumber(), documentPage.getPageSize());
        Page<IngestedDocumentSummary> summaries = ingestedDocumentService.listForChat(chatId, documentPage);

        return ResponseEntity.ok(new PagedModel<>(summaries));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<IngestedDocumentSummary> get(@PathVariable UUID chatId,
                                                       @PathVariable UUID documentId) {
        chatService.requireOwned(chatId);

        log.info("Getting document {} of chat {}", documentId, chatId);

        return ResponseEntity.ok(ingestedDocumentService.getForChat(documentId, chatId));
    }

    @PatchMapping("/{documentId}")
    public ResponseEntity<IngestedDocumentSummary> rename(@PathVariable UUID chatId,
                                                          @PathVariable UUID documentId,
                                                          @RequestBody IngestedDocumentUpdateRequest updateRequest) {
        chatService.requireOwned(chatId);

        log.info("Renaming document {} of chat {}", documentId, chatId);

        return ResponseEntity.ok(ingestedDocumentService.renameForChat(documentId, chatId, updateRequest.fileName()));
    }

    /**
     * Stops the document being retrieved in this conversation, and leaves the conversation itself
     * alone. A document that arrived on a message keeps its {@code chat_attachment} row, because
     * that row is what the message displays and removing it would rewrite history the user did not
     * ask to change — {@code DELETE /attachments/{attachmentId}} is what removes both.
     */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID chatId,
                                       @PathVariable UUID documentId) {
        chatService.requireOwned(chatId);

        log.info("Deleting document {} of chat {}", documentId, chatId);
        ingestedDocumentService.deleteForChat(documentId, chatId);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{documentId}/refresh")
    public ResponseEntity<IngestedDocumentSummary> refresh(@PathVariable UUID chatId,
                                                           @PathVariable UUID documentId) {
        chatService.requireOwned(chatId);

        log.info("Refreshing document {} of chat {}", documentId, chatId);

        return ResponseEntity.accepted().body(ingestedDocumentService.refreshForChat(documentId, chatId));
    }

    private static URI location(UUID chatId, UUID documentId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/chats/{chatId}/documents/{documentId}")
                .buildAndExpand(chatId, documentId)
                .toUri();
    }
}
