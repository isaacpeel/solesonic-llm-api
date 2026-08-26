package com.solesonic.model.chat;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * What Ollama itself reported about one assistant turn, carried on the {@code done} SSE event and
 * persisted on the message row.
 * <p>
 * Every field is copied verbatim out of Ollama's terminal {@code /api/chat} response — nothing here
 * is measured or derived by this application, so a client reading it sees the model server's own
 * accounting rather than an approximation of it. Ollama populates these only on the response where
 * {@code done} is true; earlier streamed chunks carry none of them, which is why
 * {@link ResponseMetadataCapture} waits for that one response before recording anything.
 * <p>
 * The whole record is {@code null} on a message for any turn Ollama never answered: an A2A agent
 * delegation, which never reaches a chat model at all, and a turn cancelled before the terminal
 * response arrived.
 */
public record ResponseMetadata(
        @Nullable String model,
        //Pinned to a string so it goes back out in the ISO-8601 form Ollama sent, rather than the
        //numeric timestamp a mapper left to its own defaults could choose.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Nullable Instant createdAt,
        @Nullable String doneReason,
        @Nullable Long totalDurationNanos,
        @Nullable Long loadDurationNanos,
        @Nullable Integer promptEvalCount,
        @Nullable Long promptEvalDurationNanos,
        @Nullable Integer evalCount,
        @Nullable Long evalDurationNanos) {

    static final String DONE = "done";
    static final String CREATED_AT = "created-at";
    static final String TOTAL_DURATION = "total-duration";
    static final String LOAD_DURATION = "load-duration";
    static final String PROMPT_EVAL_COUNT = "prompt-eval-count";
    static final String PROMPT_EVAL_DURATION = "prompt-eval-duration";
    static final String EVAL_COUNT = "eval-count";
    static final String EVAL_DURATION = "eval-duration";

    /**
     * Spring AI's {@code OllamaChatModel} parses Ollama's raw fields into a string-keyed metadata
     * map rather than a typed Ollama-specific class, so the keys above are the only way to reach
     * them. They mirror that class's own private constants.
     * <p>
     * The durations arrive as {@link Duration}, having been parsed from the nanoseconds Ollama
     * actually sends. They are put back on that footing here — a lossless unit change, not a
     * measurement — so the persisted and published shape is the one Ollama documents, and carries no
     * dependency on how Jackson happens to be configured to render a {@code Duration}.
     */
    public static ResponseMetadata from(ChatResponse chatResponse) {
        ChatResponseMetadata chatResponseMetadata = chatResponse.getMetadata();

        String doneReason = Optional.ofNullable(chatResponse.getResult())
                .map(Generation::getMetadata)
                .map(ChatGenerationMetadata::getFinishReason)
                .orElse(null);

        Instant createdAt = chatResponseMetadata.get(CREATED_AT);
        Integer promptEvalCount = chatResponseMetadata.get(PROMPT_EVAL_COUNT);
        Integer evalCount = chatResponseMetadata.get(EVAL_COUNT);

        return new ResponseMetadata(
                chatResponseMetadata.getModel(),
                createdAt,
                doneReason,
                nanos(chatResponseMetadata.get(TOTAL_DURATION)),
                nanos(chatResponseMetadata.get(LOAD_DURATION)),
                promptEvalCount,
                nanos(chatResponseMetadata.get(PROMPT_EVAL_DURATION)),
                evalCount,
                nanos(chatResponseMetadata.get(EVAL_DURATION)));
    }

    private static @Nullable Long nanos(@Nullable Duration duration) {
        if (duration == null) {
            return null;
        }

        return duration.toNanos();
    }
}
