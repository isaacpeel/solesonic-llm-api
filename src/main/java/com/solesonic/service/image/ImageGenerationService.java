package com.solesonic.service.image;

import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.mcp.client.IdentityToolCallback;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.model.image.ImageGenerationErrorCode;
import com.solesonic.model.image.ImageGenerationEvent;
import com.solesonic.model.image.ImageGenerationFailure;
import com.solesonic.model.image.ImageGenerationMetadata;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.solesonic.model.image.ImageGenerationErrorCode.BACKEND_UNAVAILABLE;
import static com.solesonic.model.image.ImageGenerationErrorCode.FORBIDDEN;
import static com.solesonic.model.image.ImageGenerationErrorCode.GENERATION_TIMEOUT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INTERNAL;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_PROMPT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_REFERENCE_IMAGE;
import static com.solesonic.model.image.ImageGenerationErrorCode.RATE_LIMITED;
import static com.solesonic.model.image.ImageGenerationErrorCode.REFERENCE_IMAGES_UNSUPPORTED;

/**
 * Generates an image from a prompt by calling the {@code generate_image} MCP tool directly.
 * <p>
 * The model is not involved. This is explicit generation — the user asks for an image and gets one —
 * which is what keeps the tool's two megabytes of base64 structurally incapable of reaching a
 * prompt or chat memory: there is no tool-calling loop here to feed a result back into. Agentic
 * generation, where the model decides to call the tool itself, needs a deliberate interception step
 * in the tool-callback path before it can be enabled, and is not what this class does.
 * <p>
 * Three things this class owns that the tool does not:
 * <ul>
 *     <li><strong>Identity.</strong> The call travels on the user's own token, so the role the tool
 *     requires stays a per-user control rather than a property of a shared service account.</li>
 *     <li><strong>Admission control.</strong> The image server has one GPU and no admission control
 *     of its own: concurrent calls serialize while each one occupies a request thread there for up
 *     to its full deadline. This bounds how many the API will have in flight at once.</li>
 *     <li><strong>Error mapping.</strong> Failures arrive in three shapes and leave in one — see
 *     {@link #classify}.</li>
 * </ul>
 */
@Service
public class ImageGenerationService {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    public static final String GENERATE_IMAGE_TOOL = "generate_image";
    public static final String PROMPT_ARGUMENT = "prompt";

    private static final String DEFAULT_CONTENT_TYPE = "image/png";

    /**
     * Depth bound on the cause walk in {@link #classify}: a malformed exception chain must not turn
     * a failed generation into a hang.
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * How long the stream tolerates hearing nothing at all before ending itself.
     * <p>
     * A guard, not a tuning knob, and deliberately a <em>silence</em> deadline rather than an
     * absolute one: the tool reports progress about once a second while it works, so going quiet
     * means something below has stopped answering. Set above the image server's own 180s deadline,
     * so a generation that times out there reports its own error rather than tripping this.
     * <p>
     * Without it, a blocking call that neither returns nor throws — a torn-down connection whose
     * JSON-RPC response is never delivered, say — leaves the client with no terminal frame and a
     * spinner that runs until the MCP request timeout ten minutes later.
     */
    private static final Duration SILENCE_DEADLINE = Duration.ofSeconds(200);

    private final McpSyncClient mcpSyncClient;
    private final GeneratedImageService generatedImageService;
    private final ImageGenerationProgressBroker imageGenerationProgressBroker;
    private final ImageToolCatalog imageToolCatalog;
    private final ReferenceImageInjector referenceImageInjector;
    private final Semaphore inFlightGenerations;
    private final Duration admissionTimeout;

    public ImageGenerationService(McpSyncClient mcpSyncClient,
                                  GeneratedImageService generatedImageService,
                                  ImageGenerationProgressBroker imageGenerationProgressBroker,
                                  ImageToolCatalog imageToolCatalog,
                                  ReferenceImageInjector referenceImageInjector,
                                  @Value("${solesonic.llm.image.max-concurrent}") int maxConcurrent,
                                  @Value("${solesonic.llm.image.admission-timeout}") Duration admissionTimeout) {
        this.mcpSyncClient = mcpSyncClient;
        this.generatedImageService = generatedImageService;
        this.imageGenerationProgressBroker = imageGenerationProgressBroker;
        this.imageToolCatalog = imageToolCatalog;
        this.referenceImageInjector = referenceImageInjector;
        this.inFlightGenerations = new Semaphore(maxConcurrent, true);
        this.admissionTimeout = admissionTimeout;

        log.info("Image generation admits {} concurrent generation(s), waiting up to {} beyond that",
                maxConcurrent, admissionTimeout);
    }

    /**
     * Generates an image and streams its progress.
     * <p>
     * Every frame — progress, and the single terminal {@code complete} or {@code error} — travels
     * through one sink, so the terminal frame cannot overtake the progress that preceded it.
     * <p>
     * The generation itself runs independently of the returned stream: a client that disconnects
     * halfway through stops receiving frames, but the image it paid for is still generated and
     * stored, and can be fetched by id afterwards.
     */
    public Flux<ImageGenerationEvent> stream(String prompt,
                                             String tool,
                                             List<UUID> referenceAttachmentIds,
                                             UUID userId,
                                             String userToken) {
        return Flux.defer(() -> {
            UUID generationId = UUID.randomUUID();

            imageGenerationProgressBroker.open(generationId);

            return imageGenerationProgressBroker.frames(generationId)
                    .timeout(SILENCE_DEADLINE, Flux.defer(() -> {
                        log.error("Image generation {} went silent for {}; ending the stream",
                                generationId, SILENCE_DEADLINE);

                        return Flux.just(new ImageGenerationFailure(GENERATION_TIMEOUT,
                                message(GENERATION_TIMEOUT)));
                    }))
                    .doOnSubscribe(_ -> Schedulers.boundedElastic().schedule(() ->
                            generateInto(generationId, prompt, tool, referenceAttachmentIds, userId, userToken)));
        });
    }

    /**
     * Runs one generation to its end and emits its terminal frame.
     * <p>
     * Detached from the subscription that started it on purpose: a client that closes the stream
     * halfway through should not throw away a generation the GPU has already been paid for. The
     * image is stored either way and can be fetched by id afterwards.
     */
    private void generateInto(UUID generationId,
                              String prompt,
                              String tool,
                              List<UUID> referenceAttachmentIds,
                              UUID userId,
                              String userToken) {
        try {
            GeneratedImageSummary generatedImageSummary =
                    generate(generationId, prompt, tool, referenceAttachmentIds, userId, userToken);

            imageGenerationProgressBroker.emit(generationId, generatedImageSummary);
        } catch (RuntimeException runtimeException) {
            imageGenerationProgressBroker.emit(generationId, failure(runtimeException));
        } finally {
            imageGenerationProgressBroker.close(generationId);
        }
    }

    /**
     * The non-streaming variant, for scripts and tests. Blocks for the whole generation — five to
     * fifteen seconds typically, up to the image server's deadline — and throws
     * {@link ImageGenerationException} rather than emitting a failure frame.
     * <p>
     * The generation is still opened with the broker even though nobody is watching it: an unopened
     * progress token is indistinguishable from a chat id to the shared progress callback, and would
     * have this call's progress written into chat history.
     */
    public GeneratedImageSummary generate(String prompt,
                                          String tool,
                                          List<UUID> referenceAttachmentIds,
                                          UUID userId,
                                          String userToken) {
        UUID generationId = UUID.randomUUID();

        imageGenerationProgressBroker.open(generationId);

        try {
            return generate(generationId, prompt, tool, referenceAttachmentIds, userId, userToken);
        } finally {
            imageGenerationProgressBroker.close(generationId);
        }
    }

    /**
     * @param tool the image tool the caller chose, or blank when there is only one
     */
    private GeneratedImageSummary generate(UUID generationId,
                                           String prompt,
                                           String tool,
                                           List<UUID> referenceAttachmentIds,
                                           UUID userId,
                                           String userToken) {

        String trimmedPrompt = StringUtils.trimToNull(prompt);

        //Rejected here rather than at the tool: an empty prompt is a round trip that can only ever
        //come back as a validation failure.
        if (trimmedPrompt == null) {
            throw new ImageGenerationException(INVALID_PROMPT, message(INVALID_PROMPT));
        }

        //Both ahead of admission, so a request that can never succeed does not hold a GPU slot.
        McpSchema.Tool imageTool = imageToolCatalog.resolve(tool);
        List<ChatAttachment> referenceAttachments = referenceAttachments(imageTool, referenceAttachmentIds, userId);

        admit(generationId);

        try {
            McpSchema.CallToolResult callToolResult = callTool(generationId, imageTool.name(), trimmedPrompt,
                    referenceImageInjector.payload(referenceAttachments), userToken);

            return store(callToolResult, trimmedPrompt, userId,
                    referenceAttachments.stream().map(ChatAttachment::getId).toList());
        } finally {
            inFlightGenerations.release();
        }
    }

    /**
     * Resolves an explicit request for reference images, strictly.
     * <p>
     * Unlike the chat path, which skips an attachment it cannot use and carries on, this is a request
     * the caller made on purpose: quietly generating without the references would hand back an image
     * they did not ask for. So every id must be the caller's own PNG, JPEG or WebP attachment, the
     * tool must advertise {@code reference_images} at all — an MCP server that predates them would
     * ignore the argument — and the count must be exactly what the tool takes.
     *
     * @return the attachments in the order the caller listed them; empty when none were requested
     */
    private List<ChatAttachment> referenceAttachments(McpSchema.Tool imageTool,
                                                      List<UUID> referenceAttachmentIds,
                                                      UUID userId) {
        if (referenceAttachmentIds == null || referenceAttachmentIds.isEmpty()) {
            return List.of();
        }

        int slots = referenceImageInjector.referenceSlots(imageTool.inputSchema());

        if (slots == 0) {
            throw new ImageGenerationException(REFERENCE_IMAGES_UNSUPPORTED, message(REFERENCE_IMAGES_UNSUPPORTED));
        }

        List<UUID> requested = List.copyOf(new LinkedHashSet<>(referenceAttachmentIds));

        //Before the lookup, which also bounds the ids that reach its IN clause by the slot count.
        if (requested.size() != slots) {
            throw new ImageGenerationException(INVALID_REFERENCE_IMAGE,
                    "This image style takes exactly %d reference image%s.".formatted(slots, slots == 1 ? "" : "s"));
        }

        List<ChatAttachment> attachments = referenceImageInjector.usableAttachments(userId, requested);

        if (attachments.size() != requested.size()) {
            log.warn("Refusing image generation: {} of {} reference attachment(s) are not usable images of user {}",
                    requested.size() - attachments.size(), requested.size(), userId);

            throw new ImageGenerationException(INVALID_REFERENCE_IMAGE, message(INVALID_REFERENCE_IMAGE));
        }

        return attachments;
    }

    /**
     * Bounds in-flight generations. Past the bound a caller waits, and past the wait it is told to
     * retry — which is a far better outcome than letting user load turn into exhausted request
     * threads on an MCP server whose single GPU is working through a queue anyway.
     */
    private void admit(UUID generationId) {
        boolean admitted;

        try {
            admitted = inFlightGenerations.tryAcquire(admissionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();

            throw new ImageGenerationException(INTERNAL, message(INTERNAL), interruptedException);
        }

        if (!admitted) {
            log.warn("Refusing image generation {}: no capacity after waiting {}, {} caller(s) queued",
                    generationId, admissionTimeout, inFlightGenerations.getQueueLength());

            throw new ImageGenerationException(RATE_LIMITED, message(RATE_LIMITED));
        }
    }

    /**
     * {@code progressToken} is not optional if progress is wanted: the MCP server drops every
     * notification for a request that arrived without one, which turns a five-to-fifteen second
     * generation into a silent wait.
     */
    private McpSchema.CallToolResult callTool(UUID generationId,
                                              String toolName,
                                              String prompt,
                                              List<Map<String, Object>> referenceImages,
                                              String userToken) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(PROMPT_ARGUMENT, prompt);

        if (!referenceImages.isEmpty()) {
            arguments.put(ReferenceImageInjector.REFERENCE_IMAGES, referenceImages);
        }

        McpSchema.CallToolRequest callToolRequest = McpSchema.CallToolRequest.builder(toolName)
                .arguments(arguments)
                .progressToken(generationId.toString())
                .build();

        log.info("Calling {} for generation {} with {} reference image(s)",
                toolName, generationId, referenceImages.size());

        try {
            return IdentityToolCallback.withUserToken(userToken, () -> mcpSyncClient.callTool(callToolRequest));
        } catch (RuntimeException runtimeException) {
            throw classify(generationId, runtimeException);
        }
    }

    private GeneratedImageSummary store(McpSchema.CallToolResult callToolResult,
                                        String prompt,
                                        UUID userId,
                                        List<UUID> referenceAttachmentIds) {

        List<McpSchema.Content> content = callToolResult.content() == null ? List.of() : callToolResult.content();

        //Parsed by type rather than by position. The order is stable today, but indexing on it is
        //brittle for no gain.
        String metadataText = content.stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining(System.lineSeparator()));

        if (Boolean.TRUE.equals(callToolResult.isError())) {
            throw rejected(metadataText);
        }

        McpSchema.ImageContent imageContent = content.stream()
                .filter(McpSchema.ImageContent.class::isInstance)
                .map(McpSchema.ImageContent.class::cast)
                .findFirst()
                .orElseThrow(() -> {
                    log.error("Image generation succeeded but returned no image content: {}", metadataText);

                    return new ImageGenerationException(BACKEND_UNAVAILABLE, message(BACKEND_UNAVAILABLE));
                });

        byte[] imageData = decode(imageContent);

        String contentType = StringUtils.defaultIfBlank(imageContent.mimeType(), DEFAULT_CONTENT_TYPE);

        ImageGenerationMetadata imageGenerationMetadata = ImageGenerationMetadata.parse(metadataText);

        //No chat: this is explicit generation, which is not part of a conversation.
        return generatedImageService.store(userId, null, prompt, imageData, contentType,
                imageGenerationMetadata, referenceAttachmentIds);
    }

    private static byte[] decode(McpSchema.ImageContent imageContent) {
        try {
            return Base64.getDecoder().decode(imageContent.data());
        } catch (IllegalArgumentException illegalArgumentException) {
            log.error("Image generation returned data that is not valid base64", illegalArgumentException);

            throw new ImageGenerationException(BACKEND_UNAVAILABLE, message(BACKEND_UNAVAILABLE),
                    illegalArgumentException);
        }
    }

    /**
     * The tool's own validation rejections come back as an ordinary result with {@code isError}
     * set, carrying text meant for a model rather than a person — so it is classified, logged, and
     * replaced.
     */
    private static ImageGenerationException rejected(String errorText) {
        log.warn("Image generation was rejected by the tool: {}", errorText);

        if (Strings.CI.containsAny(errorText, "prompt is required", "non-empty prompt")) {
            return new ImageGenerationException(INVALID_PROMPT, message(INVALID_PROMPT));
        }

        ImageGenerationErrorCode errorCode = fromMessage(errorText);

        //A rejection this API cannot place is still a rejection from the image backend, so it says
        //so rather than blaming the caller for a prompt that may have been fine.
        if (errorCode == null) {
            errorCode = BACKEND_UNAVAILABLE;
        }

        return new ImageGenerationException(errorCode, message(errorCode));
    }

    /**
     * Collapses a thrown failure onto the user-facing taxonomy.
     * <p>
     * The tool signals failure in more than one shape — an {@link McpError} carrying a JSON-RPC
     * error, a transport exception when the MCP server itself cannot be reached, and a result with
     * {@code isError} set (handled in {@link #rejected}). All three are tolerated here rather than
     * one being assumed, because which one a given failure takes is a property of the MCP SDK's
     * exception handling, not of this API.
     * <p>
     * The raw message can name the image server's internal prompt id and host behaviour, so it is
     * logged and never returned.
     */
    private static ImageGenerationException classify(UUID generationId, RuntimeException runtimeException) {
        log.error("Image generation {} failed", generationId, runtimeException);

        Throwable cause = runtimeException;

        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof TimeoutException) {
                return new ImageGenerationException(GENERATION_TIMEOUT, message(GENERATION_TIMEOUT), runtimeException);
            }

            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return new ImageGenerationException(BACKEND_UNAVAILABLE, message(BACKEND_UNAVAILABLE), runtimeException);
            }

            ImageGenerationErrorCode fromMessage = fromMessage(cause.getMessage());

            if (fromMessage != null) {
                return new ImageGenerationException(fromMessage, message(fromMessage), runtimeException);
            }

            if (cause == cause.getCause()) {
                break;
            }

            cause = cause.getCause();
        }

        return new ImageGenerationException(INTERNAL, message(INTERNAL), runtimeException);
    }

    /**
     * Matched on text because that is all these failures carry: the tool's exceptions and its
     * validation rejections are both prose, with no code to switch on. Deliberately conservative —
     * an unrecognised message returns null so the caller can decide, rather than guessing.
     */
    private static ImageGenerationErrorCode fromMessage(String failureText) {
        if (Strings.CI.contains(failureText, "does not accept reference images")) {
            return REFERENCE_IMAGES_UNSUPPORTED;
        }

        //The tool's own reference-image rejections, and schema validation naming the parameter.
        //A failed ComfyUI upload is worded without either, so it stays a backend failure below.
        if (Strings.CI.containsAny(failureText, "reference image", "reference_images")) {
            return INVALID_REFERENCE_IMAGE;
        }

        if (Strings.CI.containsAny(failureText, "access is denied", "access denied", "accessdenied", "forbidden")) {
            return FORBIDDEN;
        }

        if (Strings.CI.containsAny(failureText, "did not finish within", "timed out", "timeout")) {
            return GENERATION_TIMEOUT;
        }

        if (Strings.CI.containsAny(failureText, "connection", "unreachable", "refused", "comfyui")) {
            return BACKEND_UNAVAILABLE;
        }

        return null;
    }

    private static String message(ImageGenerationErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_PROMPT -> "Describe the image you want before generating one.";
            case GENERATION_TIMEOUT -> "Image generation is taking longer than expected. Please try again.";
            case BACKEND_UNAVAILABLE -> "Image generation is unavailable right now. Please try again shortly.";
            case FORBIDDEN -> "You do not have access to image generation.";
            case RATE_LIMITED -> "Too many images are being generated right now. Please try again in a moment.";
            case INVALID_REFERENCE_IMAGE ->
                    "A reference image could not be used. Attach your own PNG, JPEG or WebP images, as many as this image style takes.";
            case REFERENCE_IMAGES_UNSUPPORTED -> "This image style does not accept reference images.";
            case INVALID_IMAGE_TOOL -> "That image style is not available.";
            case IMAGE_TOOL_REQUIRED -> "Choose which image style to use.";
            case NO_IMAGE_TOOLS -> "Image generation is not configured.";
            case INTERNAL -> "Image generation failed unexpectedly. Please try again.";
        };
    }

    private static ImageGenerationFailure failure(Throwable throwable) {
        if (throwable instanceof ImageGenerationException imageGenerationException) {
            return new ImageGenerationFailure(imageGenerationException.getErrorCode(),
                    imageGenerationException.getMessage());
        }

        log.error("Unmapped image generation failure", throwable);

        return new ImageGenerationFailure(INTERNAL, message(INTERNAL));
    }
}
