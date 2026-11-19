package com.solesonic.config.openai;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TracingOpenAiHttpClientConfigTest {

    @Test
    void theDispatcherRunsTasksInTheCallersObservation() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        ExecutorService dispatcher = TracingOpenAiHttpClientConfig.tracingDispatcher();
        Observation chat = Observation.start("chat", registry);

        Observation seen;
        try (Observation.Scope _ = chat.openScope()) {
            seen = dispatcher.submit(() -> ObservationThreadLocalAccessor.getInstance().getValue()).get(5, TimeUnit.SECONDS);
        }
        Observation outside = dispatcher.submit(() -> ObservationThreadLocalAccessor.getInstance().getValue()).get(5, TimeUnit.SECONDS);

        assertThat(seen).isSameAs(chat);
        assertThat(outside).isNull();
        chat.stop();
        dispatcher.shutdown();
    }
}
