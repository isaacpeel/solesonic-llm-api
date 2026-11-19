package com.solesonic.config.logging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import reactor.util.context.Context;

import java.util.function.Function;

/**
 * Keeps a chat turn in the trace of the request that started it.
 * <p>
 * A turn runs detached ({@code subscribe()} on its own), so neither the caller's thread locals nor its Reactor context
 * reach it. {@link TurnMdc} carries the log ids across; this carries the current {@link Observation}, under the key
 * Spring AI's chat client and Reactor's automatic context propagation read their parent from. Without it the model
 * call, the tool calls and LiteLLM each start a trace of their own.
 */
public final class TurnTrace {

    private TurnTrace() {
    }

    /**
     * The observation current on the calling thread (the request's), or null outside one.
     */
    public static @Nullable Observation capture() {
        return ObservationThreadLocalAccessor.getInstance().getValue();
    }

    /**
     * For {@code contextWrite}: makes {@code parent} the current observation for everything upstream, unless a
     * subscriber further down already put a more specific one there.
     */
    public static Function<Context, Context> into(@Nullable Observation parent) {
        return context -> parent == null || context.hasKey(ObservationThreadLocalAccessor.KEY)
                ? context
                : context.put(ObservationThreadLocalAccessor.KEY, parent);
    }

    /**
     * The parent a detached run should use: what the subscriber's context carries, else what the caller had.
     */
    public static @Nullable Observation parent(reactor.util.context.ContextView context, @Nullable Observation caller) {
        return context.getOrDefault(ObservationThreadLocalAccessor.KEY, caller);
    }
}
