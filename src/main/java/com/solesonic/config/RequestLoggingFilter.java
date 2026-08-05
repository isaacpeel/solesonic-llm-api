package com.solesonic.config;

import com.solesonic.util.logging.Redactor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static java.util.Objects.requireNonNullElse;

/**
 * One structured line per request, written when the request completes.
 * <p>
 * Logging before the chain runs — which is what this filter used to do — cannot say whether the
 * request was served or rejected: no status, no duration, no outcome. A rejected request looked
 * exactly like a successful one.
 * <p>
 * Fields are attached as key/value pairs rather than interpolated into a sentence, because the
 * whole point of the ECS JSON application log is that nothing downstream has to re-parse prose.
 * <p>
 * {@code client.ip} is deliberately not among them: {@link com.solesonic.config.logging.MdcRequestFilter}
 * already puts it in the MDC, so it is on every line of the request anyway. Boot's ECS formatter
 * merges MDC entries and key/value pairs into one nested-pair block, so adding it a second time
 * makes {@code ContextPairs} throw {@code Duplicate nested pairs added under 'client.ip'} and the
 * line is dropped by every appender. That only shows up under the prod profiles, which are the
 * only ones wired to the structured appenders — locally the plain encoder never notices.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String STREAMING_PATH_PREFIX = "/streaming/";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();

        // A streaming turn is long-lived by design, so its completion line arrives only when the
        // emitter closes. Without this, a live turn is indistinguishable from a hang.
        if (isStreaming(request)) {
            log.atInfo()
                    .addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("url.path", request.getRequestURI())
                    .log("stream opened");
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            log.atInfo()
                    .addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("url.path", request.getRequestURI())
                    .addKeyValue("url.query", requireNonNullElse(Redactor.redactQuery(request.getQueryString()), ""))
                    .addKeyValue("http.response.status_code", response.getStatus())
                    .addKeyValue("event.duration", System.nanoTime() - startedAt)
                    .log("request completed");
        }
    }

    private static boolean isStreaming(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (requestUri == null) {
            return false;
        }

        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.startsWith(STREAMING_PATH_PREFIX, contextPath.length());
        }

        return requestUri.startsWith(STREAMING_PATH_PREFIX);
    }
}
