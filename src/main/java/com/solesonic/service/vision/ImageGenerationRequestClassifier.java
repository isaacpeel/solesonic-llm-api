package com.solesonic.service.vision;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.solesonic.config.openai.ToolCallOpenAiConfig.TOOL_CALL_CHAT_MODEL;

/**
 * Decides, from the user's own words, whether a plain chat message asks for an image to be generated.
 * <p>
 * When it does, the attached images are only inputs for {@code generate_image}, so the vision model
 * is never called for them. When it does not, they are described as always; if the chat model then
 * generates an image anyway, that vision call was unneeded, which is accepted. The decision model is
 * the tool-call routing model, asked one yes/no question in a short blocking text-only call; the chat
 * model is never asked. Anything other than a clear yes, including a failed call, is a no.
 * <p>
 * The prompt carries no options of its own: {@code OpenAiChatModel} only applies its configured
 * defaults, the model name among them, to a prompt without options, and replaces them wholesale
 * otherwise, so a prompt with options and no model reaches the server with no model at all.
 * <p>
 * Blocking, and called inside the turn's credential scope, which is what carries the user's key.
 */
@Service
public class ImageGenerationRequestClassifier {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationRequestClassifier.class);

    private static final int LOGGED_ANSWER_LENGTH = 40;
    private static final String YES = "YES";

    private final OpenAiChatModel decisionChatModel;
    private final String instruction;
    private final String decisionModel;

    public ImageGenerationRequestClassifier(@Qualifier(TOOL_CALL_CHAT_MODEL) OpenAiChatModel decisionChatModel,
                                            @Value("classpath:prompts/image-generation-request-prompt.st") Resource instruction,
                                            @Value("${solesonic.llm.tool-call.model}") String decisionModel) {
        this.decisionChatModel = decisionChatModel;
        this.instruction = readInstruction(instruction);
        this.decisionModel = decisionModel;
    }

    private static String readInstruction(Resource instruction) {
        try {
            return instruction.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ioException) {
            throw new IllegalStateException("Could not read the image generation request prompt", ioException);
        }
    }

    public boolean requestsImageGeneration(String userMessage) {
        if (StringUtils.isBlank(userMessage)) {
            log.info("No words to classify, not an image generation request");

            return false;
        }

        log.debug("Classifying whether this asks for an image to be generated: {}", userMessage);

        long startedAt = System.nanoTime();

        try {
            Prompt prompt = new Prompt(List.of(new SystemMessage(instruction), new UserMessage(userMessage)));

            String answer = answer(decisionChatModel.call(prompt));
            boolean requestsImageGeneration = isYes(answer);

            log.info("Decision model {} answered image generation request = {} in {} ms; raw answer: [{}]",
                    decisionModel, requestsImageGeneration, elapsedMillis(startedAt),
                    StringUtils.abbreviate(answer.strip(), LOGGED_ANSWER_LENGTH));

            return requestsImageGeneration;
        } catch (RuntimeException runtimeException) {
            log.warn("Decision model {} could not classify the request after {} ms, treating it as not an image generation request: {}: {}",
                    decisionModel, elapsedMillis(startedAt), runtimeException.getClass().getSimpleName(),
                    runtimeException.getMessage());

            return false;
        }
    }

    private static String answer(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return "";
        }

        Generation generation = chatResponse.getResult();

        if (generation == null) {
            return "";
        }

        return Objects.toString(generation.getOutput().getText(), "");
    }

    static boolean isYes(String answer) {
        String normalized = answer.strip().toUpperCase(Locale.ROOT);

        return normalized.startsWith(YES);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
