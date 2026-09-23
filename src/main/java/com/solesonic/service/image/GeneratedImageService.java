package com.solesonic.service.image;

import com.solesonic.model.image.GeneratedImage;
import com.solesonic.model.image.GeneratedImageSummary;
import com.solesonic.model.image.ImageGenerationMetadata;
import com.solesonic.repository.image.GeneratedImageRepository;
import com.solesonic.scope.UserRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Stores generated images and reads them back.
 * <p>
 * This is the boundary the plan's storage decision lands on: the image tool persists nothing and
 * returns base64, so the bytes are decoded exactly once — here — and everything downstream carries
 * a {@link GeneratedImageSummary} of a few hundred bytes instead.
 * <p>
 * They live in Postgres rather than an object store because that is already where
 * {@code chat_attachment} keeps image bytes, and a second storage backend for the same kind of data
 * is a deployment dependency this feature does not need. Images are not deduplicated by digest:
 * with a fresh random seed per call, two generations producing identical bytes is not a case worth
 * losing per-image provenance for.
 */
@Service
public class GeneratedImageService {
    private static final Logger log = LoggerFactory.getLogger(GeneratedImageService.class);

    private static final String SHA_256 = "SHA-256";
    private static final String IMAGES_PATH = "/images/";

    private final GeneratedImageRepository generatedImageRepository;
    private final UserRequestContext userRequestContext;
    private final String contextPath;

    /**
     * @param contextPath the servlet context path, which is all an image URL needs beyond the id.
     *                    Built from configuration rather than from the current request, because
     *                    most images are stored on a thread with no request bound: the tool that
     *                    generates them runs mid-turn on a {@code boundedElastic} thread. The
     *                    result is a context-relative path, which also survives being served behind
     *                    a proxy that rewrites the host.
     */
    public GeneratedImageService(GeneratedImageRepository generatedImageRepository,
                                 UserRequestContext userRequestContext,
                                 @Value("${server.servlet.context-path}") String contextPath) {
        this.generatedImageRepository = generatedImageRepository;
        this.userRequestContext = userRequestContext;
        this.contextPath = contextPath;
    }

    /**
     * Writes one generated image and returns the reference the client gets.
     * <p>
     * {@code userId} is a parameter rather than read from {@link UserRequestContext} because this
     * runs on a {@code boundedElastic} thread, where the request scope is not bound — the same
     * reason {@code ChatAttachmentService.bind} takes one.
     *
     * @param chatId the conversation the image was generated in, or null for explicit generation.
     *               An image with a chat is left unbound to a message: the assistant turn it
     *               belongs to does not exist yet, and {@link #bind} claims it once it does
     */
    @Transactional
    public GeneratedImageSummary store(UUID userId,
                                       UUID chatId,
                                       String prompt,
                                       byte[] imageData,
                                       String contentType,
                                       ImageGenerationMetadata imageGenerationMetadata) {

        GeneratedImage generatedImage = new GeneratedImage();
        generatedImage.setUserId(userId);
        generatedImage.setChatId(chatId);
        generatedImage.setPrompt(prompt);
        generatedImage.setModel(imageGenerationMetadata.model());
        generatedImage.setSeed(imageGenerationMetadata.seed());
        generatedImage.setWidth(imageGenerationMetadata.width());
        generatedImage.setHeight(imageGenerationMetadata.height());
        generatedImage.setSteps(imageGenerationMetadata.steps());
        generatedImage.setElapsedSeconds(imageGenerationMetadata.elapsedSeconds());
        generatedImage.setSha256(sha256(imageData));
        generatedImage.setContentType(contentType);
        generatedImage.setImageData(imageData);
        generatedImage.setFileSizeBytes(imageData.length);
        generatedImage.setCreated(ZonedDateTime.now());

        GeneratedImage stored = generatedImageRepository.save(generatedImage);

        log.info("Stored generated image {} ({} bytes, seed {}) for user {} on chat {}",
                stored.getId(), stored.getFileSizeBytes(), stored.getSeed(), userId, chatId);

        return summary(stored);
    }

    @Transactional(readOnly = true)
    public GeneratedImage get(UUID imageId) {
        UUID userId = userRequestContext.getUserId();

        return generatedImageRepository.findByIdAndUserId(imageId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Generated image not found: " + imageId));
    }

    @Transactional(readOnly = true)
    public GeneratedImageSummary metadata(UUID imageId) {
        return summary(get(imageId));
    }

    /**
     * Every image bound to a turn of this conversation, for hydrating history.
     */
    @Transactional(readOnly = true)
    public List<GeneratedImageSummary> forChat(UUID chatId) {
        return withImageUrls(generatedImageRepository.findSummariesByChatId(chatId));
    }

    /**
     * Discards every image generated inside a conversation that is being deleted.
     * <p>
     * Not user-scoped, unlike {@link #get(UUID)}: the caller has already established that the
     * conversation is theirs, and an image carries a {@code chatId} only when it was generated in
     * that conversation. It joins the caller's transaction, so a chat and its images go together or
     * not at all.
     */
    @Transactional
    public void deleteForChat(UUID chatId) {
        int deleted = generatedImageRepository.deleteByChatId(chatId);

        if (deleted > 0) {
            log.info("Deleted {} generated image(s) of chat {}", deleted, chatId);
        }
    }

    /**
     * Discards every image generated for one message that is being deleted.
     * <p>
     * Not user-scoped, unlike {@link #get(UUID)}: the caller has already established that the
     * message's chat is theirs. A plain bulk delete — unlike an attachment, an image has no
     * vector-store or {@code ingested_document} dependents to sweep first.
     */
    @Transactional
    public void deleteForChatMessage(UUID chatMessageId) {
        int deleted = generatedImageRepository.deleteByChatMessageId(chatMessageId);

        if (deleted > 0) {
            log.info("Deleted {} generated image(s) of chat message {}", deleted, chatMessageId);
        }
    }

    /**
     * Images generated since a turn began — what that turn's {@code done} payload carries.
     */
    @Transactional(readOnly = true)
    public List<GeneratedImageSummary> forChatSince(UUID chatId, ZonedDateTime since) {
        return withImageUrls(generatedImageRepository.findSummariesByChatIdSince(chatId, since));
    }

    /**
     * Attaches every unbound image of this chat to the assistant turn that has just been written.
     * <p>
     * Transactional because it has to be: a bulk update needs an active transaction, and the caller
     * — the chat memory advisor, saving the assistant message — does not run in one.
     */
    @Transactional
    public void bind(UUID chatId, UUID chatMessageId) {
        int bound = generatedImageRepository.bind(chatId, chatMessageId);

        if (bound > 0) {
            log.info("Bound {} generated image(s) to chat message {}", bound, chatMessageId);
        }
    }

    public GeneratedImageSummary summary(GeneratedImage generatedImage) {
        return new GeneratedImageSummary(
                generatedImage.getId(),
                generatedImage.getUserId(),
                generatedImage.getChatMessageId(),
                imageUrl(generatedImage.getId()),
                generatedImage.getName(),
                generatedImage.getPrompt(),
                generatedImage.getModel(),
                generatedImage.getSeed(),
                generatedImage.getWidth(),
                generatedImage.getHeight(),
                generatedImage.getSteps(),
                generatedImage.getElapsedSeconds(),
                generatedImage.getFileSizeBytes(),
                generatedImage.getCreated());
    }

    /**
     * The caller's own images, newest first.
     */
    @Transactional(readOnly = true)
    public Page<GeneratedImageSummary> list(UUID userId, Pageable pageable) {
        return withImageUrls(generatedImageRepository.findSummaryPageByUserId(userId, pageable));
    }

    /**
     * Every user's images, newest first, for the {@code image-admin} role. {@code userIdFilter}
     * narrows the listing to one user without needing a separate endpoint.
     */
    @Transactional(readOnly = true)
    public Page<GeneratedImageSummary> listAll(UUID userIdFilter, Pageable pageable) {
        Page<GeneratedImageSummary> summaries = userIdFilter == null
                ? generatedImageRepository.findSummaryPage(pageable)
                : generatedImageRepository.findSummaryPageByUserId(userIdFilter, pageable);

        return withImageUrls(summaries);
    }

    /**
     * Renames the caller's own image. {@code 404} rather than {@code 403} for an image belonging to
     * someone else, the same stance {@link #get(UUID)} takes.
     */
    @Transactional
    public GeneratedImageSummary renameForUser(UUID imageId, UUID userId, String name) {
        GeneratedImage generatedImage = generatedImageRepository.findByIdAndUserId(imageId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Generated image not found: " + imageId));

        return rename(generatedImage, name);
    }

    /**
     * Renames any user's image, for the {@code image-admin} role.
     */
    @Transactional
    public GeneratedImageSummary rename(UUID imageId, String name) {
        GeneratedImage generatedImage = generatedImageRepository.findById(imageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Generated image not found: " + imageId));

        return rename(generatedImage, name);
    }

    /**
     * Deletes the caller's own image.
     */
    @Transactional
    public void deleteForUser(UUID imageId, UUID userId) {
        GeneratedImage generatedImage = generatedImageRepository.findByIdAndUserId(imageId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Generated image not found: " + imageId));

        generatedImageRepository.delete(generatedImage);
        log.info("Deleted generated image {} for user {}", imageId, userId);
    }

    /**
     * Deletes any user's image, for the {@code image-admin} role.
     */
    @Transactional
    public void delete(UUID imageId) {
        GeneratedImage generatedImage = generatedImageRepository.findById(imageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Generated image not found: " + imageId));

        generatedImageRepository.delete(generatedImage);
        log.info("Deleted generated image {}", imageId);
    }

    private GeneratedImageSummary rename(GeneratedImage generatedImage, String name) {
        generatedImage.setName(validName(name));

        log.info("Renaming generated image {} to {}", generatedImage.getId(), generatedImage.getName());

        return summary(generatedImageRepository.save(generatedImage));
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A name is required");
        }

        return name.strip();
    }

    private List<GeneratedImageSummary> withImageUrls(List<GeneratedImageSummary> summaries) {
        return summaries.stream()
                .map(summary -> summary.withImageUrl(imageUrl(summary.imageId())))
                .toList();
    }

    private Page<GeneratedImageSummary> withImageUrls(Page<GeneratedImageSummary> summaries) {
        return summaries.map(summary -> summary.withImageUrl(imageUrl(summary.imageId())));
    }

    private String imageUrl(UUID imageId) {
        return contextPath + IMAGES_PATH + imageId;
    }

    /**
     * SHA-256 is mandated by the platform, so its absence is a broken JVM rather than a runtime
     * condition worth a checked path.
     */
    private static String sha256(byte[] imageData) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(SHA_256);

            return HexFormat.of().formatHex(messageDigest.digest(imageData));
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 is not available", noSuchAlgorithmException);
        }
    }
}
