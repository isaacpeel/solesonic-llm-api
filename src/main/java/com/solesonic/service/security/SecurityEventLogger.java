package com.solesonic.service.security;

import com.solesonic.model.security.SecurityEvent;
import com.solesonic.model.security.SecurityEventReason;
import com.solesonic.util.logging.Redactor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The only place the security log's grammar exists.
 * <p>
 * One line per event, fixed field order, fixed arity, no prose:
 * <pre>
 * 2026-08-02T14:03:11.442Z SECURITY event=authn.failure ip=203.0.113.10 method=GET path="/actuator/env" status=401 reason=missing_token route=unknown
 * </pre>
 * The timestamp and the {@code SECURITY} marker come from the appender pattern rather than the
 * message, so a caller cannot forge them, and every attacker-influenced field goes through
 * {@link Redactor} first. Nothing free-form — no header, no body, no query string — is ever written
 * here: this file is parsed by fail2ban, and a line an attacker can choose is a firewall an
 * attacker can drive.
 */
@Component
public class SecurityEventLogger {

    /**
     * A dedicated logger name bound to its own appender with {@code additivity="false"}, so one
     * event is exactly one line in exactly one file and a jail can never double-count.
     */
    private static final Logger securityLog = LoggerFactory.getLogger("security.audit");

    /**
     * The application's route prefixes, matched against the path with the servlet context path
     * removed. This is what separates the aggressive jail from the tolerant one: in prod the filter
     * chain authenticates every request, so a scanner asking for {@code /.env} is rejected with 401
     * and never reaches a controller — there is no 404 to match on. The distinction has to be made
     * where the rejection happens.
     */
    private static final List<String> KNOWN_ROUTE_PREFIXES = List.of(
            "/streaming/chats",
            "/chats",
            "/chatgroups",
            "/attachments",
            "/images",
            "/documents",
            "/trainingdocuments",
            "/ollama",
            "/users",
            "/slash",
            "/atlassian",
            "/confluence",
            "/broker");

    private static final String ROUTE_KNOWN = "known";
    private static final String ROUTE_UNKNOWN = "unknown";

    public void log(SecurityEvent securityEvent,
                    HttpServletRequest request,
                    int status,
                    SecurityEventReason reason) {
        securityLog.info("SECURITY event={} ip={} method={} path=\"{}\" status={} reason={} route={}",
                securityEvent.key(),
                // Never the X-Forwarded-For header. Under prod-nginx, server.forward-headers-strategy
                // resolves this to the real client; under prod it is already the true peer. One
                // place, one policy.
                request.getRemoteAddr(),
                Redactor.sanitizeMethod(request.getMethod()),
                Redactor.sanitizePath(request.getRequestURI()),
                status,
                reason.key(),
                routeClassification(request));
    }

    private static String routeClassification(HttpServletRequest request) {
        String routePath = withoutContextPath(request);

        boolean known = KNOWN_ROUTE_PREFIXES.stream()
                .anyMatch(prefix -> routePath.equals(prefix) || routePath.startsWith(prefix + "/"));

        return known ? ROUTE_KNOWN : ROUTE_UNKNOWN;
    }

    private static String withoutContextPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();

        if (requestUri == null) {
            return "";
        }

        String contextPath = request.getContextPath();

        if (contextPath == null || contextPath.isEmpty() || !requestUri.startsWith(contextPath)) {
            return requestUri;
        }

        return requestUri.substring(contextPath.length());
    }
}
