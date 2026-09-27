package com.solesonic.util;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Normalizes formatting quirks some models emit in place of the token a real newline would be,
 * such as a literal {@code <br>} tag.
 * <p>
 * Applied to the one {@code Flux<String>} every chat route funnels through
 * ({@code RedisStreamingChatService#streamTurn}), so it reaches both the streamed chunks and the
 * persisted message built from them, regardless of which route produced the text.
 * <p>
 * A tag can split across a chunk boundary, so this holds back a trailing partial tag until the
 * next chunk resolves it rather than matching per chunk in isolation.
 */
public final class ResponseSanitizer {
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final int MAX_HELD_CHARS = 8;

    private ResponseSanitizer() {
    }

    public static Function<Flux<String>, Flux<String>> sanitize() {
        return chunks -> Flux.defer(() -> {
            StringBuilder held = new StringBuilder();

            return chunks
                    .handle((String chunk, SynchronousSink<String> sink) -> {
                        held.append(chunk);

                        String buffered = BR_TAG.matcher(held).replaceAll("\n");
                        held.setLength(0);

                        int safeEmitIndex = safeEmitIndex(buffered);

                        if (safeEmitIndex > 0) {
                            sink.next(buffered.substring(0, safeEmitIndex));
                        }

                        held.append(buffered.substring(safeEmitIndex));
                    })
                    .concatWith(Mono.defer(() -> {
                        String remainder = held.toString();

                        return remainder.isEmpty() ? Mono.empty() : Mono.just(remainder);
                    }));
        });
    }

    /**
     * The prefix of {@code text} safe to emit now: everything except a trailing, still-unterminated
     * {@code <...} that could still complete into a tag this class matches on the next chunk.
     */
    private static int safeEmitIndex(String text) {
        int lastOpenAngleIndex = text.lastIndexOf('<');

        if (lastOpenAngleIndex < 0) {
            return text.length();
        }

        boolean unterminated = text.indexOf('>', lastOpenAngleIndex) < 0;
        boolean withinHoldWindow = text.length() - lastOpenAngleIndex <= MAX_HELD_CHARS;

        return (unterminated && withinHoldWindow) ? lastOpenAngleIndex : text.length();
    }
}
