package com.solesonic.service.image;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.image.GeneratedImageBytes;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.model.image.ImageGenerationEvent;
import com.solesonic.model.image.ImageGenerationFailure;
import com.solesonic.model.image.ImageGenerationMetadata;
import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.service.llm.UserLlmKeyService;
import com.solesonic.service.user.UserProfileService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.solesonic.model.image.ImageGenerationErrorCode.GENERATION_TIMEOUT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INTERNAL;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_PROMPT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_REFERENCE_IMAGE;
import static com.solesonic.model.image.ImageGenerationErrorCode.RATE_LIMITED;

/**
 * Generates an image through LiteLLM on the user's own model key, and stores it.
 * <p>
 * Three things this class owns that the image service does not:
 * <ul>
 *     <li><strong>Identity.</strong> Every call carries the caller's key, so which users may make
 *     images is LiteLLM's per-key model allowlist.</li>
 *     <li><strong>Admission control.</strong> The image service has one GPU and no admission control
 *     of its own. This bounds how many generations the API has in flight at once.</li>
 *     <li><strong>Reference resolution.</strong> Explicit generation refuses any reference it cannot
 *     use; chat skips it and carries on.</li>
 * </ul>
 */
@Service
public class ImageGenerationService {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    /**
     * Added to the admission wait and the read timeout so that a generation the HTTP client times out
     * reports its own {@code GENERATION_TIMEOUT} rather than tripping this guard first.
     */
    private static final Duration DEADLINE_MARGIN = Duration.ofSeconds(30);

    private final LiteLlmImageClient liteLlmImageClient;
    private final GeneratedImageService generatedImageService;
    private final ReferenceImageResolver referenceImageResolver;
    private final UserLlmKeyService userLlmKeyService;
    private final UserProfileService userProfileService;
    private final Semaphore inFlightGenerations;
    private final Duration admissionTimeout;
    private final Duration streamDeadline;
    private final int maxReferenceImages;

    public ImageGenerationService(LiteLlmImageClient liteLlmImageClient,
                                  GeneratedImageService generatedImageService,
                                  ReferenceImageResolver referenceImageResolver,
                                  UserLlmKeyService userLlmKeyService,
                                  UserProfileService userProfileService,
                                  @Value("${solesonic.llm.image.max-concurrent}") int maxConcurrent,
                                  @Value("${solesonic.llm.image.admission-timeout}") Duration admissionTimeout,
                                  @Value("${solesonic.llm.image.read-timeout}") Duration readTimeout,
                                  @Value("${solesonic.llm.image.max-reference-images}") int maxReferenceImages) {
        this.liteLlmImageClient = liteLlmImageClient;
        this.generatedImageService = generatedImageService;
        this.referenceImageResolver = referenceImageResolver;
        this.userLlmKeyService = userLlmKeyService;
        this.userProfileService = userProfileService;
        this.inFlightGenerations = new Semaphore(maxConcurrent, true);
        this.admissionTimeout = admissionTimeout;
        this.streamDeadline = admissionTimeout.plus(readTimeout).plus(DEADLINE_MARGIN);
        this.maxReferenceImages = maxReferenceImages;

        log.info("Image generation admits {} concurrent generation(s), waiting up to {} beyond that",
                maxConcurrent, admissionTimeout);
    }

    /**
     * Generates an image and streams its single terminal frame.
     * <p>
     * The key is looked up here, on the request thread, so a user without one is refused with a
     * status before the response is committed. The generation itself runs detached from the returned
     * stream: a client that disconnects stops receiving, but the image is still generated and stored.
     * <p>
     * Nothing reports progress while the image service works, so the deadline is absolute.
     */
    public Flux<ImageGenerationEvent> stream(String prompt, List<UUID> referenceAttachmentIds, UUID userId) {
        LlmApiKey llmApiKey = userLlmKeyService.requireKey(userId);

        return Flux.defer(() -> {
            Sinks.One<ImageGenerationEvent> terminalFrame = Sinks.one();

            Thread.ofVirtual().name("image-generation").start(() -> {
                try {
                    terminalFrame.tryEmitValue(outcome(prompt, referenceAttachmentIds, userId, llmApiKey));
                } finally {
                    terminalFrame.tryEmitValue(new ImageGenerationFailure(INTERNAL, INTERNAL.userMessage()));
                }
            });

            return terminalFrame.asMono()
                    .timeout(streamDeadline, Mono.fromSupplier(() -> {
                        log.error("Image generation produced nothing within {}; ending the stream", streamDeadline);

                        return new ImageGenerationFailure(GENERATION_TIMEOUT, GENERATION_TIMEOUT.userMessage());
                    }))
                    .flux();
        });
    }

    /**
     * The non-streaming variant, for scripts and tests. Throws {@link ImageGenerationException} rather
     * than emitting a failure frame.
     */
    public GeneratedImageSummary generate(String prompt, List<UUID> referenceAttachmentIds, UUID userId) {
        String trimmedPrompt = requirePrompt(prompt);
        LlmApiKey llmApiKey = userLlmKeyService.requireKey(userId);

        return generate(trimmedPrompt, strictReferences(referenceAttachmentIds, userId), userId, null, llmApiKey);
    }

    /**
     * Generation the chat model or {@code /generate_image} asked for, stored against the chat. An
     * attachment that cannot be used is skipped rather than failing the turn, and at most the
     * service's limit is sent.
     */
    public GeneratedImageSummary generateInChat(String prompt, List<UUID> referenceAttachmentIds, UUID userId, UUID chatId) {
        String trimmedPrompt = requirePrompt(prompt);
        LlmApiKey llmApiKey = userLlmKeyService.requireKey(userId);

        return generate(trimmedPrompt, lenientReferences(referenceAttachmentIds, userId), userId, chatId, llmApiKey);
    }

    private ImageGenerationEvent outcome(String prompt, List<UUID> referenceAttachmentIds, UUID userId, LlmApiKey llmApiKey) {
        try {
            return generate(requirePrompt(prompt), strictReferences(referenceAttachmentIds, userId), userId, null, llmApiKey);
        } catch (ImageGenerationException imageGenerationException) {
            return new ImageGenerationFailure(imageGenerationException.getErrorCode(), imageGenerationException.getMessage());
        } catch (RuntimeException runtimeException) {
            log.error("Unmapped image generation failure", runtimeException);

            return new ImageGenerationFailure(INTERNAL, INTERNAL.userMessage());
        }
    }

    private GeneratedImageSummary generate(String prompt,
                                           List<ChatAttachment> references,
                                           UUID userId,
                                           UUID chatId,
                                           LlmApiKey llmApiKey) {
        admit();

        try {
            long started = System.nanoTime();

            log.info("Generating an image for user {} with {} reference image(s)", userId, references.size());

            String archiveUser = archiveUser(userId);

            GeneratedImageBytes generatedImageBytes = references.isEmpty()
                    ? liteLlmImageClient.generate(prompt, llmApiKey, archiveUser)
                    : liteLlmImageClient.edit(prompt, references, llmApiKey, archiveUser);

            double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;

            ImageGenerationMetadata imageGenerationMetadata = new ImageGenerationMetadata(generatedImageBytes.model(),
                    null, generatedImageBytes.width(), generatedImageBytes.height(), null, elapsedSeconds);

            return generatedImageService.store(userId, chatId, prompt, generatedImageBytes.imageData(),
                    generatedImageBytes.contentType(), imageGenerationMetadata,
                    references.stream().map(ChatAttachment::getId).toList());
        } finally {
            inFlightGenerations.release();
        }
    }

    private static String requirePrompt(String prompt) {
        String trimmedPrompt = StringUtils.trimToNull(prompt);

        if (trimmedPrompt == null) {
            throw new ImageGenerationException(INVALID_PROMPT, INVALID_PROMPT.userMessage());
        }

        return trimmedPrompt;
    }

    /**
     * An explicit request for references is a request, not a hint: generating without them would hand
     * back an image the caller did not ask for. So every id must be the caller's own usable image.
     */
    private List<ChatAttachment> strictReferences(List<UUID> referenceAttachmentIds, UUID userId) {
        if (referenceAttachmentIds == null || referenceAttachmentIds.isEmpty()) {
            return List.of();
        }

        List<UUID> requested = List.copyOf(new LinkedHashSet<>(referenceAttachmentIds));

        if (requested.size() > maxReferenceImages) {
            throw new ImageGenerationException(INVALID_REFERENCE_IMAGE,
                    "At most %d reference images are accepted.".formatted(maxReferenceImages));
        }

        List<ChatAttachment> attachments = referenceImageResolver.usableAttachments(userId, requested);

        if (attachments.size() != requested.size()) {
            log.warn("Refusing image generation: {} of {} reference attachment(s) are not usable images of user {}",
                    requested.size() - attachments.size(), requested.size(), userId);

            throw new ImageGenerationException(INVALID_REFERENCE_IMAGE, INVALID_REFERENCE_IMAGE.userMessage());
        }

        return attachments;
    }

    private List<ChatAttachment> lenientReferences(List<UUID> referenceAttachmentIds, UUID userId) {
        List<ChatAttachment> attachments = referenceImageResolver.usableAttachments(userId, referenceAttachmentIds);

        if (attachments.size() > maxReferenceImages) {
            log.info("{} usable image attachment(s) but the image service takes {}; sending the first {}",
                    attachments.size(), maxReferenceImages, maxReferenceImages);

            return attachments.subList(0, maxReferenceImages);
        }

        return attachments;
    }

    /**
     * Past the bound a caller waits, and past the wait it is told to retry — far better than letting
     * user load pile up against a single GPU working through a queue anyway.
     */
    private void admit() {
        boolean admitted;

        try {
            admitted = inFlightGenerations.tryAcquire(admissionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();

            throw new ImageGenerationException(INTERNAL, INTERNAL.userMessage(), interruptedException);
        }

        if (!admitted) {
            log.warn("Refusing image generation: no capacity after waiting {}, {} caller(s) queued",
                    admissionTimeout, inFlightGenerations.getQueueLength());

            throw new ImageGenerationException(RATE_LIMITED, RATE_LIMITED.userMessage());
        }
    }

    /**
     * Who the image service files the image under in its audit archive: the username recorded from
     * the user's token, or the user id when no profile or username has been recorded.
     */
    private String archiveUser(UUID userId) {
        return userProfileService.username(userId)
                .filter(username -> !username.isBlank())
                .orElse(userId.toString());
    }
}
