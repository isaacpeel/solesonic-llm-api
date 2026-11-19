package com.solesonic.config.openai;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps every LiteLLM call in the trace of the chat that made it.
 * <p>
 * The OpenAI SDK's OkHttp transport runs asynchronous (streaming) calls on its dispatcher's threads, where the
 * caller's current observation is not set, so the outbound request carried no {@code traceparent} and LiteLLM's spans
 * started a trace of their own. The dispatcher here hands each task the caller's thread locals (the current
 * observation and the MDC) through Micrometer's context propagation; the registry lets the transport observe the call.
 * Every OpenAI client in this app collects all {@link OpenAiHttpClientBuilderCustomizer} beans, so this reaches them all.
 */
@Configuration
public class TracingOpenAiHttpClientConfig {

    @Bean
    public OpenAiHttpClientBuilderCustomizer tracingOpenAiHttpClientCustomizer(ObservationRegistry observationRegistry) {
        ExecutorService dispatcher = tracingDispatcher();
        return builder -> builder.observationRegistry(observationRegistry).dispatcherExecutorService(dispatcher);
    }

    static ExecutorService tracingDispatcher() {
        AtomicInteger threads = new AtomicInteger();
        ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "openai-http-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        return ContextExecutorService.wrap(pool, ContextSnapshotFactory.builder().build());
    }
}
