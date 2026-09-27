package com.solesonic.api.image;

import com.solesonic.model.image.GenerateImageRequest;
import com.solesonic.model.image.GeneratedImage;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.model.image.GeneratedImageUpdateRequest;
import com.solesonic.model.image.ImageGenerationEvent;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.image.GeneratedImageService;
import com.solesonic.service.image.ImageGenerationService;
import com.solesonic.util.AuthenticationTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Text-to-image generation and the images it produces.
 * <p>
 * Generation is offered twice over the same path, chosen by the {@code Accept} header: as SSE,
 * which is the one worth using — a generation takes five to fifteen seconds and the progress frames
 * are what make that wait legible — and as plain JSON for scripts and tests.
 */
@RestController
@RequestMapping(GeneratedImageController.IMAGES)
public class GeneratedImageController {
    private static final Logger log = LoggerFactory.getLogger(GeneratedImageController.class);

    static final String IMAGES = "/images";

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ImageGenerationService imageGenerationService;
    private final GeneratedImageService generatedImageService;
    private final UserRequestContext userRequestContext;

    public GeneratedImageController(ImageGenerationService imageGenerationService,
                                    GeneratedImageService generatedImageService,
                                    UserRequestContext userRequestContext) {
        this.imageGenerationService = imageGenerationService;
        this.generatedImageService = generatedImageService;
        this.userRequestContext = userRequestContext;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> generateStreaming(@RequestBody GenerateImageRequest generateImageRequest,
                                                      Authentication authentication) {
        log.info("Streaming image generation for user {}", userRequestContext.getUserId());

        return imageGenerationService
                .stream(generateImageRequest.prompt(), userRequestContext.getUserId(),
                        AuthenticationTokens.token(authentication))
                .map(GeneratedImageController::serverSentEvent);
    }

    /**
     * Its own path rather than the streaming path negotiated by {@code Accept}: a client that
     * accepts any media type would match both handlers equally well, and Spring rejects that as an
     * ambiguous mapping rather than picking one.
     */
    @PostMapping(value = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GeneratedImageSummary> generate(@RequestBody GenerateImageRequest generateImageRequest,
                                                          Authentication authentication) {
        log.info("Generating image for user {}", userRequestContext.getUserId());

        GeneratedImageSummary generatedImageSummary = imageGenerationService
                .generate(generateImageRequest.prompt(), userRequestContext.getUserId(),
                        AuthenticationTokens.token(authentication));

        return ResponseEntity.created(URI.create(generatedImageSummary.imageUrl())).body(generatedImageSummary);
    }

    /**
     * The bytes, served under the same authorization as the generation that produced them. Long
     * cache lifetimes are safe because an image is immutable once written; the digest is its
     * {@code ETag}, so a history reload revalidates rather than re-downloads.
     */
    @GetMapping("/{imageId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID imageId) {
        log.info("Downloading generated image {}", imageId);

        GeneratedImage generatedImage = generatedImageService.get(imageId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(generatedImage.getContentType()))
                .eTag("\"" + generatedImage.getSha256() + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(generatedImage.getImageData());
    }

    /**
     * Provenance without the bytes — the prompt, the seed, and the timings — for rendering an image
     * that arrived long before the current page load.
     */
    @GetMapping("/{imageId}/metadata")
    public ResponseEntity<GeneratedImageSummary> metadata(@PathVariable UUID imageId) {
        return ResponseEntity.ok(generatedImageService.metadata(imageId));
    }

    /**
     * The caller's own images, newest first — the management listing.
     */
    @GetMapping
    public ResponseEntity<PagedModel<GeneratedImageSummary>> list(
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        Pageable imagePage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Listing generated images for user {} page {} size {}",
                userRequestContext.getUserId(), imagePage.getPageNumber(), imagePage.getPageSize());

        Page<GeneratedImageSummary> summaries =
                generatedImageService.list(userRequestContext.getUserId(), imagePage);

        return ResponseEntity.ok(new PagedModel<>(summaries));
    }

    /**
     * Renames the caller's own image. Rename only — the bytes and provenance never change.
     */
    @PatchMapping("/{imageId}")
    public ResponseEntity<GeneratedImageSummary> rename(@PathVariable UUID imageId,
                                                        @RequestBody GeneratedImageUpdateRequest updateRequest) {
        log.info("Renaming generated image {} for user {}", imageId, userRequestContext.getUserId());

        return ResponseEntity.ok(generatedImageService
                .renameForUser(imageId, userRequestContext.getUserId(), updateRequest.name()));
    }

    /**
     * Deletes the caller's own image.
     */
    @DeleteMapping("/{imageId}")
    public ResponseEntity<Void> delete(@PathVariable UUID imageId) {
        log.info("Deleting generated image {} for user {}", imageId, userRequestContext.getUserId());
        generatedImageService.deleteForUser(imageId, userRequestContext.getUserId());

        return ResponseEntity.noContent().build();
    }

    /**
     * Every user's images, newest first. {@code userId} narrows the listing to one user's images,
     * covering the support case of "show me this one user's images" without a separate endpoint.
     */
    @PreAuthorize("hasRole('image-admin')")
    @GetMapping("/admin")
    public ResponseEntity<PagedModel<GeneratedImageSummary>> listAll(
            @RequestParam(required = false) UUID userId,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        Pageable imagePage = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        log.info("Listing generated images page {} size {} user {}",
                imagePage.getPageNumber(), imagePage.getPageSize(), userId);

        Page<GeneratedImageSummary> summaries = generatedImageService.listAll(userId, imagePage);

        return ResponseEntity.ok(new PagedModel<>(summaries));
    }

    /**
     * Renames any user's image, for moderation.
     */
    @PreAuthorize("hasRole('image-admin')")
    @PatchMapping("/admin/{imageId}")
    public ResponseEntity<GeneratedImageSummary> renameAny(@PathVariable UUID imageId,
                                                           @RequestBody GeneratedImageUpdateRequest updateRequest) {
        log.info("Renaming generated image {}", imageId);

        return ResponseEntity.ok(generatedImageService.rename(imageId, updateRequest.name()));
    }

    /**
     * Deletes any user's image, for moderation.
     */
    @PreAuthorize("hasRole('image-admin')")
    @DeleteMapping("/admin/{imageId}")
    public ResponseEntity<Void> deleteAny(@PathVariable UUID imageId) {
        log.info("Deleting generated image {}", imageId);
        generatedImageService.delete(imageId);

        return ResponseEntity.noContent().build();
    }

    private static ServerSentEvent<?> serverSentEvent(ImageGenerationEvent imageGenerationEvent) {
        return ServerSentEvent.builder()
                .event(imageGenerationEvent.eventName())
                .data(imageGenerationEvent)
                .build();
    }
}
