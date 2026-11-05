package com.solesonic.config.logging;

import org.slf4j.MDC;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The correlation fields of one chat turn, carried through the turn's whole reactive pipeline.
 * <p>
 * A turn is started with a detached {@code subscribe()}, so it inherits no Reactor context and, with it, no MDC:
 * without this, everything logged while a turn streams or fails — Spring AI's {@code MessageAggregator}, the
 * stream error, the failure notification — carries neither the request's ids nor which chat it belongs to.
 * The snapshot taken here is written into the turn's context under {@link MdcThreadLocalAccessor#KEY}, which
 * automatic context propagation ({@link ReactorMdcPropagationConfig}) restores around every operator.
 * <p>
 * {@link #CHAT_ID} is what lets the log agent tie a failure to the chat a user asks about.
 */
public final class TurnMdc {

    public static final String CHAT_ID = "chat.id";

    private TurnMdc() {
    }

    /**
     * The calling thread's MDC (request id, user, trace) plus the chat id.
     */
    public static Map<String, String> capture(UUID chatId) {
        Map<String, String> current = MDC.getCopyOfContextMap();
        Map<String, String> snapshot = current == null ? new HashMap<>() : new HashMap<>(current);

        if (chatId != null) {
            snapshot.put(CHAT_ID, chatId.toString());
        }

        return Map.copyOf(snapshot);
    }

    /**
     * For {@code contextWrite}: puts the snapshot in the context on top of what a subscriber further down already
     * put there, so the snapshot nearest the code that logs wins.
     */
    public static Function<Context, Context> into(Map<String, String> snapshot) {
        return context -> context.put(MdcThreadLocalAccessor.KEY, merged(context, snapshot));
    }

    private static Map<String, String> merged(ContextView context, Map<String, String> snapshot) {
        Map<String, String> existing = context.getOrDefault(MdcThreadLocalAccessor.KEY, Map.of());
        Map<String, String> merged = new HashMap<>(existing);
        merged.putAll(snapshot);

        return Map.copyOf(merged);
    }
}
