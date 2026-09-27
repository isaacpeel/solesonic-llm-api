package com.solesonic.api.document;

import com.solesonic.model.document.UriIngestRequest;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.ingestion.IngestedDocumentUpdateRequest;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.ingestion.UriIngestionService;
import com.solesonic.service.security.ResourceOwnershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
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
 * One user's own documents: retrievable in their conversations and nobody else's.
 * <p>
 * Every method opens with {@code resourceOwnershipService.isOwner(userId, request)}, the same stance
 * {@code UserController} takes for {@code /users/{userId}/preferences} — the path names an owner, so
 * the path is checked against the JWT subject before anything else happens. No role gating: this
 * collection is self-service, and a user adding to their own retrievable material changes nothing
 * anyone else sees. The one exception is {@code promote/global}, which additionally requires the
 * {@code rag-admin} role — moving a document into the shared corpus changes what everyone else sees,
 * not just the caller's own material.
 * <p>
 * The {@code 403} and the {@code 404} answer different questions and neither leaks the other's. A
 * {@code 403} means the path named someone other than the caller — a value the caller supplied
 * themselves, so there is nothing to conceal. A {@code 404} means no such document in <em>this</em>
 * collection, which is the same answer whether it never existed or belongs to another user, because
 * the ownership lives in the repository's {@code where} clause rather than in a comparison made
 * after the row is in hand.
 */
@RestController
@RequestMapping("/users/{userId}/documents")
public class IngestedUserDocumentController {
    private static final Logger log = LoggerFactory.getLogger(IngestedUserDocumentController.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final IngestedDocumentService ingestedDocumentService;
    private final UriIngestionService uriIngestionService;
    private final ResourceOwnershipService resourceOwnershipService;

    public IngestedUserDocumentController(IngestedDocumentService ingestedDocumentService,
                                          UriIngestionService uriIngestionService,
                                          ResourceOwnershipService resourceOwnershipService) {
        this.ingestedDocumentService = ingestedDocumentService;
        this.uriIngestionService = uriIngestionService;
        this.resourceOwnershipService = resourceOwnershipService;
    }

    @PostMapping
    public ResponseEntity<IngestedDocumentSummary> upload(@PathVariable UUID userId,
                                                          @RequestParam MultipartFile file,
                                                          HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Queuing a document for user {}", userId);
        IngestedDocumentSummary summary = ingestedDocumentService.queueForUser(file, userId);

        return ResponseEntity.created(location(userId, summary.id())).body(summary);
    }

    @PostMapping("/uri")
    public ResponseEntity<IngestedDocumentSummary> ingestUri(@PathVariable UUID userId,
                                                             @RequestBody UriIngestRequest uriIngestRequest,
                                                             HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Queuing a uri for user {}", userId);
        IngestedDocument ingestedDocument =
                uriIngestionService.queueForUser(uriIngestRequest.uri(), userId);

        IngestedDocumentSummary summary = ingestedDocumentService.summaryOf(ingestedDocument);

        return ResponseEntity.accepted().location(location(userId, summary.id())).body(summary);
    }

    @GetMapping
    public ResponseEntity<PagedModel<IngestedDocumentSummary>> list(
            @PathVariable UUID userId,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable,
            HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // The window only, for the reason IngestedGlobalDocumentController.list gives.
        Pageable documentPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Listing documents for user {} page {} size {}",
                userId, documentPage.getPageNumber(), documentPage.getPageSize());
        Page<IngestedDocumentSummary> summaries = ingestedDocumentService.listForUser(userId, documentPage);

        return ResponseEntity.ok(new PagedModel<>(summaries));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<IngestedDocumentSummary> get(@PathVariable UUID userId,
                                                       @PathVariable UUID documentId,
                                                       HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Getting document {} for user {}", documentId, userId);

        return ResponseEntity.ok(ingestedDocumentService.getForUser(documentId, userId));
    }

    @PatchMapping("/{documentId}")
    public ResponseEntity<IngestedDocumentSummary> rename(@PathVariable UUID userId,
                                                          @PathVariable UUID documentId,
                                                          @RequestBody IngestedDocumentUpdateRequest updateRequest,
                                                          HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Renaming document {} for user {}", documentId, userId);

        return ResponseEntity.ok(ingestedDocumentService.renameForUser(documentId, userId, updateRequest.fileName()));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID userId,
                                       @PathVariable UUID documentId,
                                       HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Deleting document {} for user {}", documentId, userId);
        ingestedDocumentService.deleteForUser(documentId, userId);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{documentId}/refresh")
    public ResponseEntity<IngestedDocumentSummary> refresh(@PathVariable UUID userId,
                                                           @PathVariable UUID documentId,
                                                           HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Refreshing document {} for user {}", documentId, userId);

        return ResponseEntity.accepted().body(ingestedDocumentService.refreshForUser(documentId, userId));
    }

    /**
     * Moves the document into the shared corpus, where everyone can retrieve it.
     * <p>
     * Ownership is still checked first, the same as every other method here — the path names the
     * document's owner, and that owner must also hold {@code rag-admin} to promote it. Management of
     * a promoted document passes to the {@code rag-admin} role, same as {@code IngestedChatDocumentController}'s
     * {@code promote/global}, whose service call this reuses: {@code IngestedDocumentService.promoteToGlobal}
     * checks the caller manages the document, which a user document's own uploader already does.
     */
    @PreAuthorize("hasRole('rag-admin')")
    @PostMapping("/{documentId}/promote/global")
    public ResponseEntity<IngestedDocumentSummary> promoteToGlobal(@PathVariable UUID userId,
                                                                    @PathVariable UUID documentId,
                                                                    HttpServletRequest request) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Promoting document {} for user {} to the shared corpus", documentId, userId);

        return ResponseEntity.ok(ingestedDocumentService.promoteToGlobal(documentId, userId));
    }

    private static URI location(UUID userId, UUID documentId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/users/{userId}/documents/{documentId}")
                .buildAndExpand(userId, documentId)
                .toUri();
    }
}
