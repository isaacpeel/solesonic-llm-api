package com.solesonic.config.logging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

class TurnTraceTest {

    @Test
    void theCallersObservationIsCapturedAndReachesADetachedRun() {
        ObservationRegistry registry = registry();
        Observation request = Observation.start("http.server.requests", registry);
        Observation captured;
        try (Observation.Scope _ = request.openScope()) {
            captured = TurnTrace.capture();
        }
        assertThat(captured).isSameAs(request);
        assertThat(TurnTrace.capture()).isNull();

        Observation seen = Mono.deferContextual(context -> Mono.justOrEmpty(
                        context.<Observation>getOrEmpty(ObservationThreadLocalAccessor.KEY)))
                .contextWrite(TurnTrace.into(captured))
                .block();
        assertThat(seen).isSameAs(request);
        request.stop();
    }

    @Test
    void aMoreSpecificParentDownstreamWins() {
        ObservationRegistry registry = registry();
        Observation outer = Observation.start("outer", registry);
        Observation inner = Observation.start("inner", registry);

        Context context = TurnTrace.into(outer).apply(Context.of(ObservationThreadLocalAccessor.KEY, inner));
        assertThat(context.<Observation>get(ObservationThreadLocalAccessor.KEY)).isSameAs(inner);
        assertThat(TurnTrace.parent(Context.empty(), outer)).isSameAs(outer);
        assertThat(TurnTrace.into(null).apply(Context.empty()).isEmpty()).isTrue();
    }

    private static ObservationRegistry registry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        return registry;
    }
}
