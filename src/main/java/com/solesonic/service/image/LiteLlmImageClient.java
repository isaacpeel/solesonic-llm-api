package com.solesonic.service.image;

import com.openai.client.OpenAIClient;
import com.openai.core.MultipartField;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.images.Image;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import com.solesonic.exception.image.ImageGenerationException;
import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.image.GeneratedImageBytes;
import com.solesonic.model.image.ImageGenerationErrorCode;
import com.solesonic.model.llm.LlmApiKey;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.solesonic.config.openai.ImageOpenAiConfig.IMAGE_OPENAI_CLIENT;
import static com.solesonic.model.image.ImageGenerationErrorCode.BACKEND_UNAVAILABLE;
import static com.solesonic.model.image.ImageGenerationErrorCode.FORBIDDEN;
import static com.solesonic.model.image.ImageGenerationErrorCode.GENERATION_TIMEOUT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INTERNAL;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_PROMPT;
import static com.solesonic.model.image.ImageGenerationErrorCode.INVALID_REFERENCE_IMAGE;
import static com.solesonic.model.image.ImageGenerationErrorCode.RATE_LIMITED;

/**
 * Generates images through LiteLLM's OpenAI image routes. The only class that knows the SDK's image
 * types.
 * <p>
 * Every call carries the user's own key as a per-request header, and names the user in OpenAI's
 * {@code user} field, which LiteLLM passes through to the image service so it can file the image in
 * that user's archive directory. Failures leave as one
 * {@link ImageGenerationErrorCode}; the upstream detail can name hosts and internal behaviour, so it
 * is logged and never returned.
 */
@Component
public class LiteLlmImageClient {
    private static final Logger log = LoggerFactory.getLogger(LiteLlmImageClient.class);

    private static final String PNG = "image/png";
    private static final int MAX_CAUSE_DEPTH = 10;
    private static final Pattern SIZE = Pattern.compile("(\\d+)x(\\d+)");
    private static final Pattern PARAM = Pattern.compile("param['\"]?\\s*[:=]\\s*['\"]?([A-Za-z_\\[\\]]+)");

    private static final String NO_WORKFLOW = "no_workflow";
    private static final String NO_USABLE_WORKFLOW = "no usable comfyui workflow";
    private static final String GENERATION_TIMED_OUT = "generation_timeout";
    private static final String COMFYUI_UNAVAILABLE = "comfyui_unavailable";
    private static final String PROMPT = "prompt";
    private static final String IMAGE = "image";

    /**
     * The only parameter names logged: anything else found in an upstream body may be echoed prompt text.
     */
    private static final Set<String> KNOWN_PARAMS = Set.of(PROMPT, IMAGE, "image[]", "mask", "model", "n", "size",
            "output_format", "stream");

    private static final String NO_WORKFLOW_MESSAGE =
            "No image style accepts that many reference images. Try again with a different number.";

    private final OpenAIClient openAiClient;
    private final String generationModel;
    private final String editModel;

    public LiteLlmImageClient(@Qualifier(IMAGE_OPENAI_CLIENT) OpenAIClient openAiClient,
                              @Value("${solesonic.llm.image.generation-model}") String generationModel,
                              @Value("${solesonic.llm.image.edit-model}") String editModel) {
        this.openAiClient = openAiClient;
        this.generationModel = generationModel;
        this.editModel = editModel;
    }

    /**
     * @param user the username, or the user id when there is none
     */
    public GeneratedImageBytes generate(String prompt, LlmApiKey llmApiKey, String user) {
        ImageGenerateParams imageGenerateParams = ImageGenerateParams.builder()
                .model(generationModel)
                .prompt(prompt)
                .n(1L)
                .user(user)
                .putAdditionalHeader(HttpHeaders.AUTHORIZATION, llmApiKey.bearer())
                .build();

        return call(() -> openAiClient.images().generate(imageGenerateParams), generationModel, false);
    }

    /**
     * Each reference goes as one {@code image[]} file, in order. The service detects each file's type
     * from its bytes, so the one content type and file name the SDK puts on every part only have to be
     * plausible; the first attachment's are used.
     *
     * @param user the username, or the user id when there is none
     */
    public GeneratedImageBytes edit(String prompt, List<ChatAttachment> references, LlmApiKey llmApiKey, String user) {
        List<InputStream> imageStreams = references.stream()
                .<InputStream>map(attachment -> new ByteArrayInputStream(attachment.getFileData()))
                .toList();

        String contentType = references.getFirst().getContentType().toLowerCase(Locale.ROOT);

        MultipartField<ImageEditParams.Image> imageField = MultipartField.<ImageEditParams.Image>builder()
                .value(ImageEditParams.Image.ofInputStreams(imageStreams))
                .contentType(contentType)
                .filename(IMAGE + "." + extension(contentType))
                .build();

        ImageEditParams imageEditParams = ImageEditParams.builder()
                .image(imageField)
                .model(editModel)
                .prompt(prompt)
                .n(1L)
                .user(user)
                .putAdditionalHeader(HttpHeaders.AUTHORIZATION, llmApiKey.bearer())
                .build();

        return call(() -> openAiClient.images().edit(imageEditParams), editModel, true);
    }

    private GeneratedImageBytes call(Supplier<ImagesResponse> request, String model, boolean edit) {
        ImagesResponse imagesResponse;

        try {
            imagesResponse = request.get();
        } catch (OpenAIException openAiException) {
            throw classify(openAiException, edit);
        }

        return decode(imagesResponse, model);
    }

    private static GeneratedImageBytes decode(ImagesResponse imagesResponse, String model) {
        String base64 = imagesResponse.data().orElse(List.of()).stream()
                .findFirst()
                .flatMap(Image::b64Json)
                .orElseThrow(() -> {
                    log.error("The image service answered without an image");

                    return new ImageGenerationException(BACKEND_UNAVAILABLE, BACKEND_UNAVAILABLE.userMessage());
                });

        byte[] imageData;

        try {
            imageData = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException illegalArgumentException) {
            log.error("The image service returned data that is not valid base64", illegalArgumentException);

            throw new ImageGenerationException(BACKEND_UNAVAILABLE, BACKEND_UNAVAILABLE.userMessage(),
                    illegalArgumentException);
        }

        Matcher sizeMatcher = SIZE.matcher(imagesResponse.size().map(ImagesResponse.Size::asString).orElse(""));

        if (!sizeMatcher.matches()) {
            return new GeneratedImageBytes(imageData, PNG, null, null, model);
        }

        return new GeneratedImageBytes(imageData, PNG, Integer.valueOf(sizeMatcher.group(1)),
                Integer.valueOf(sizeMatcher.group(2)), model);
    }

    private static ImageGenerationException classify(OpenAIException openAiException, boolean edit) {
        if (openAiException instanceof OpenAIServiceException openAiServiceException) {
            return fromService(openAiServiceException, edit);
        }

        log.error("Image generation could not reach the image service", openAiException);

        Throwable cause = openAiException;

        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof InterruptedIOException || cause instanceof TimeoutException) {
                return failure(GENERATION_TIMEOUT, openAiException);
            }

            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return failure(BACKEND_UNAVAILABLE, openAiException);
            }

            if (cause == cause.getCause()) {
                break;
            }

            cause = cause.getCause();
        }

        return failure(INTERNAL, openAiException);
    }

    /**
     * LiteLLM re-wraps the image service's errors, so the service's own code may arrive only inside
     * LiteLLM's message. It is looked for there as well as in the status.
     */
    private static ImageGenerationException fromService(OpenAIServiceException openAiServiceException, boolean edit) {
        int status = openAiServiceException.statusCode();
        String detail = (openAiServiceException.getMessage() + " " + openAiServiceException.body()).toLowerCase(Locale.ROOT);
        Optional<String> param = openAiServiceException.param().or(() -> param(detail));

        log.warn("The image service refused a generation: status {}, param {}",
                status, param.filter(KNOWN_PARAMS::contains).orElse("unknown"));
        log.debug("Image service error body: {}", openAiServiceException.body());

        if (status == 401 || status == 403) {
            return failure(FORBIDDEN, openAiServiceException);
        }

        if (status == 429) {
            return failure(RATE_LIMITED, openAiServiceException);
        }

        if (Strings.CS.containsAny(detail, NO_WORKFLOW, NO_USABLE_WORKFLOW)) {
            return edit
                    ? new ImageGenerationException(INVALID_REFERENCE_IMAGE, NO_WORKFLOW_MESSAGE, openAiServiceException)
                    : failure(BACKEND_UNAVAILABLE, openAiServiceException);
        }

        if (status == 400) {
            if (param.filter(PROMPT::equals).isPresent()) {
                return failure(INVALID_PROMPT, openAiServiceException);
            }

            if (param.filter(name -> name.startsWith(IMAGE)).isPresent()) {
                return failure(INVALID_REFERENCE_IMAGE, openAiServiceException);
            }

            return failure(INTERNAL, openAiServiceException);
        }

        if (status == 413 && edit) {
            return failure(INVALID_REFERENCE_IMAGE, openAiServiceException);
        }

        if (status == 408 || status == 504 || Strings.CS.contains(detail, GENERATION_TIMED_OUT)) {
            return failure(GENERATION_TIMEOUT, openAiServiceException);
        }

        if (status == 502 || status == 503 || Strings.CS.contains(detail, COMFYUI_UNAVAILABLE)) {
            return failure(BACKEND_UNAVAILABLE, openAiServiceException);
        }

        return failure(INTERNAL, openAiServiceException);
    }

    private static Optional<String> param(String detail) {
        Matcher matcher = PARAM.matcher(detail);

        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static ImageGenerationException failure(ImageGenerationErrorCode errorCode, Throwable cause) {
        return new ImageGenerationException(errorCode, errorCode.userMessage(), cause);
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }
}
