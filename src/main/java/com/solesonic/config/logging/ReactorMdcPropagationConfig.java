package com.solesonic.config.logging;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Makes the MDC survive a Reactor thread hop.
 * <p>
 * Registering the accessor alone is not enough — Reactor has to be told to capture thread locals at
 * subscribe time and restore them around operator invocations. That is what
 * {@link Hooks#enableAutomaticContextPropagation()} switches on, and it is a global setting, so it
 * is done exactly once here rather than per subscription.
 */
@Configuration
public class ReactorMdcPropagationConfig {
    private static final Logger log = LoggerFactory.getLogger(ReactorMdcPropagationConfig.class);

    @PostConstruct
    public void enableMdcPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcThreadLocalAccessor());
        Hooks.enableAutomaticContextPropagation();

        log.info("MDC propagation across Reactor thread hops enabled");
    }
}
