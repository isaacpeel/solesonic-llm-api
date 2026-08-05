package com.solesonic.config.logging;

import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Carries the MDC across the thread hops the streaming path makes on purpose.
 * <p>
 * {@code RedisStreamingChatService.publishToRedisStream} subscribes on its own scheduler and the
 * response subscribes to the Redis stream separately, so without this everything logged during a
 * turn — which is most of what matters — would have no correlation fields at all.
 * <p>
 * Registered with the micrometer context registry by {@link ReactorMdcPropagationConfig}.
 */
public class MdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    public static final String KEY = "solesonic.mdc";

    @Override
    @NonNull
    public Object key() {
        return KEY;
    }

    @Override
    public Map<String, String> getValue() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public void setValue(@NonNull Map<String, String> contextMap) {
        MDC.setContextMap(contextMap);
    }

    @Override
    public void setValue() {
        MDC.clear();
    }
}
