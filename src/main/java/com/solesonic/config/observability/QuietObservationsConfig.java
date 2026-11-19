package com.solesonic.config.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * Keeps traces to the work a request (or a real background job) does, so a chat's trace is easy to find in HyperDX.
 * <p>
 * Dropped:
 * <ul>
 *   <li>the polling scheduled tasks in {@code solesonic.observability.quiet-tasks} (named {@code bean.method}, e.g.
 *   {@code documentIngestionSchedulingTask.embedDocuments}): they run every few seconds and almost always find
 *   nothing to do;</li>
 *   <li>Redis commands, outbound HTTP calls and embedding calls that belong to no trace: what those polling tasks
 *   (and connection housekeeping) do once the task itself is not traced.</li>
 * </ul>
 * Everything inside a request, and every other scheduled task (interest news runs, user lifecycle), is kept.
 */
@Configuration
public class QuietObservationsConfig {

    static final Set<String> QUIET_WHEN_PARENTLESS = Set.of("http.client.requests", "gen_ai.client.operation",
            "spring.ai.embedding.model.operation");

    @Bean
    ObservationPredicate quietBackgroundObservations(
            @Value("${solesonic.observability.quiet-tasks:}") List<String> quietTasks) {
        Set<String> quiet = Set.copyOf(quietTasks.stream().map(String::trim).filter(StringUtils::hasText).toList());
        return (name, context) -> keep(name, context, quiet);
    }

    static boolean keep(String name, Observation.Context context, Set<String> quietTasks) {
        if (context instanceof ScheduledTaskObservationContext task) {
            String taskName = StringUtils.uncapitalize(task.getTargetClass().getSimpleName()) + "." + task.getMethod().getName();
            return !quietTasks.contains(taskName);
        }
        if (context.getParentObservation() != null) {
            return true;
        }
        return !(isRedis(context) || QUIET_WHEN_PARENTLESS.contains(name));
    }

    private static boolean isRedis(Observation.Context context) {
        return context.getClass().getName().startsWith("io.lettuce.");
    }
}
