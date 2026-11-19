package com.solesonic.config.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuietObservationsConfigTest {

    private static final Set<String> QUIET = Set.of("pollingTask.poll");

    static class PollingTask {
        public void poll() {
        }
    }

    static class NewsTask {
        public void run() {
        }
    }

    private static ScheduledTaskObservationContext task(Object bean, String method) throws NoSuchMethodException {
        return new ScheduledTaskObservationContext(bean, bean.getClass().getMethod(method));
    }

    @Test
    void quietPollingTasksAreDroppedAndOtherTasksKept() throws NoSuchMethodException {
        assertThat(QuietObservationsConfig.keep("tasks.scheduled.execution", task(new PollingTask(), "poll"), QUIET)).isFalse();
        assertThat(QuietObservationsConfig.keep("tasks.scheduled.execution", task(new NewsTask(), "run"), QUIET)).isTrue();
    }

    @Test
    void parentlessClientCallsAreDroppedButKeptInsideATrace() {
        Observation.Context parentless = new Observation.Context();
        assertThat(QuietObservationsConfig.keep("http.client.requests", parentless, QUIET)).isFalse();
        assertThat(QuietObservationsConfig.keep("gen_ai.client.operation", parentless, QUIET)).isFalse();
        assertThat(QuietObservationsConfig.keep("http.server.requests", parentless, QUIET)).isTrue();
        assertThat(QuietObservationsConfig.keep("spring.ai.chat.client", parentless, QUIET)).isTrue();

        ObservationRegistry registry = registry();
        Observation.Context child = new Observation.Context();
        child.setParentObservation(Observation.start("http.server.requests", registry));
        assertThat(QuietObservationsConfig.keep("http.client.requests", child, QUIET)).isTrue();
    }

    private static ObservationRegistry registry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        return registry;
    }
}
