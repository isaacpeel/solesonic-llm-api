package com.solesonic.api.document;

import com.solesonic.model.document.UriIngestRequest;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.model.ingestion.IngestedDocumentSummary;
import com.solesonic.model.ingestion.IngestedDocumentUpdateRequest;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.ingestion.UriIngestionService;
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
 * The shared corpus: documents every user can retrieve, in every conversation.
 * <p>
 * There is no {@code {userId}} in these paths and nothing to check one against, for the reason
 * {@code ChatGroupController} gives for the same absence — a {@code GLOBAL} document has no owner,
 * so an owner segment would be a value with no meaning rather than one to verify.
 * <p>
 * What replaces it is a role. Every method that writes carries
 * {@code @PreAuthorize("hasRole('rag-admin')")}, the idiom {@code AtlassianTokenBrokerController}
 * uses for {@code token-mint-jira}: adding to the shared corpus changes what the assistant tells
 * every user, which is an operator action rather than a self-service one. Reads carry no annotation
 * at all — these documents are already retrievable by everyone through RAG regardless of role, so
 * gating the list would hide only the record of what the assistant already answers from.
 * <p>
 * The scope is the collection. Nothing here takes a {@code scope} parameter, and there is no request
 * shape that can create a document at another scope by accident.
 */
@RestController
@RequestMapping("/documents/global")
public class IngestedGlobalDocumentController {
    private static final Logger log = LoggerFactory.getLogger(IngestedGlobalDocumentController.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final IngestedDocumentService ingestedDocumentService;
    private final UriIngestionService uriIngestionService;
    private final UserRequestContext userRequestContext;

    public IngestedGlobalDocumentController(IngestedDocumentService ingestedDocumentService,
                                            UriIngestionService uriIngestionService,
                                            UserRequestContext userRequestContext) {
        this.ingestedDocumentService = ingestedDocumentService;
        this.uriIngestionService = uriIngestionService;
        this.userRequestContext = userRequestContext;
    }

    @PreAuthorize("hasRole('rag-admin')")
    @PostMapping
    public ResponseEntity<IngestedDocumentSummary> upload(@RequestParam MultipartFile file) {
        log.info("Queuing a shared document");
        IngestedDocumentSummary summary = ingestedDocumentService.queueGlobal(file, userRequestContext.getUserId());

        return ResponseEntity.created(location(summary.id())).body(summary);
    }

    @PreAuthorize("hasRole('rag-admin')")
    @PostMapping("/uri")
    public ResponseEntity<IngestedDocumentSummary> ingestUri(@RequestBody UriIngestRequest uriIngestRequest) {
        log.info("Queuing a shared uri");
        IngestedDocument ingestedDocument =
                uriIngestionService.queueGlobal(uriIngestRequest.uri(), userRequestContext.getUserId());

        IngestedDocumentSummary summary = ingestedDocumentService.summaryOf(ingestedDocument);

        return ResponseEntity.accepted().location(location(summary.id())).body(summary);
    }

    @GetMapping
    public ResponseEntity<PagedModel<IngestedDocumentSummary>> list(
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        // Only the window is taken from the request, the same as ChatGroupController.chats: the
        // ordering belongs to the repository query, and a caller-supplied sort appended to it is
        // either an unknown property or a perturbation that paging cannot rely on.
        Pageable documentPage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Listing shared documents page {} size {}",
                documentPage.getPageNumber(), documentPage.getPageSize());
        Page<IngestedDocumentSummary> summaries = ingestedDocumentService.listGlobal(documentPage);

        return ResponseEntity.ok(new PagedModel<>(summaries));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IngestedDocumentSummary> get(@PathVariable UUID id) {
        log.info("Getting shared document {}", id);

        return ResponseEntity.ok(ingestedDocumentService.getGlobal(id));
    }

    /**
     * Rename, and nothing else. Re-running extraction over the stored content is {@code refresh}'s
     * job, and replacing the content outright is not an operation this collection offers — a
     * different file is a different document.
     */
    @PreAuthorize("hasRole('rag-admin')")
    @PatchMapping("/{id}")
    public ResponseEntity<IngestedDocumentSummary> rename(@PathVariable UUID id,
                                                          @RequestBody IngestedDocumentUpdateRequest updateRequest) {
        log.info("Renaming shared document {}", id);

        return ResponseEntity.ok(ingestedDocumentService.renameGlobal(id, updateRequest.fileName()));
    }

    @PreAuthorize("hasRole('rag-admin')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        log.info("Deleting shared document {}", id);
        ingestedDocumentService.deleteGlobal(id);

        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('rag-admin')")
    @PostMapping("/{id}/refresh")
    public ResponseEntity<IngestedDocumentSummary> refresh(@PathVariable UUID id) {
        log.info("Refreshing shared document {}", id);

        return ResponseEntity.accepted().body(ingestedDocumentService.refreshGlobal(id));
    }

    private static URI location(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/documents/global/{id}")
                .buildAndExpand(id)
                .toUri();
    }
}
