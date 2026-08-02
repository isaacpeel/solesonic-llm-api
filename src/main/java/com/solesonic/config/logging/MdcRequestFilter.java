package com.solesonic.config.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation fields for the <em>application</em> log only.
 * <p>
 * These land in the ECS JSON as ordinary fields; they are deliberately absent from the security
 * log, which is a fixed-arity format by design.
 * <p>
 * Ordered ahead of the Spring Security filter chain (which registers at
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER}, -100), so everything logged during a request —
 * including rejections from inside the chain — carries the correlation fields.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcRequestFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "request.id";
    public static final String CLIENT_IP = "client.ip";
    public static final String USER_ID = "user.id";

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * An inbound correlation id is attacker-controlled, so it is honoured only in a shape that
     * cannot carry a newline, a quote, or unbounded length. Anything else gets a generated id.
     */
    private static final Pattern ALLOWED_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        MDC.put(REQUEST_ID, requestId(request));
        MDC.put(CLIENT_IP, request.getRemoteAddr());

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Explicit removes rather than MDC.clear(): the request thread is pooled, and clearing
            // wholesale would also drop anything a downstream component put there deliberately.
            MDC.remove(REQUEST_ID);
            MDC.remove(CLIENT_IP);
            MDC.remove(USER_ID);
        }
    }

    private static String requestId(HttpServletRequest request) {
        String inboundRequestId = request.getHeader(REQUEST_ID_HEADER);

        if (inboundRequestId != null && ALLOWED_REQUEST_ID.matcher(inboundRequestId).matches()) {
            return inboundRequestId;
        }

        return UUID.randomUUID().toString();
    }
}
