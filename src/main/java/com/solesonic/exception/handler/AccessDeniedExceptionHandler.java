package com.solesonic.exception.handler;

import com.solesonic.model.security.SecurityEventReason;
import com.solesonic.service.security.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import static com.solesonic.model.security.SecurityEvent.AUTHORIZATION_DENIED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

/**
 * Turns a method-security refusal into the status code it means.
 * <p>
 * {@code SecurityConfig} registers an {@code AccessDeniedHandler} on the filter chain, but that one
 * only ever sees an {@link AccessDeniedException} that escaped the {@code DispatcherServlet}. A
 * {@code @PreAuthorize} on a controller method throws from inside the handler invocation, where
 * {@code @ExceptionHandler} resolution runs first — and {@link GeneralExceptionHandler} declares a
 * catch-all on {@code RuntimeException} that would answer the refusal with a chat-shaped
 * {@code 200}. A role check that returns success is worse than no role check, because nothing
 * downstream can tell the difference.
 * <p>
 * {@link Ordered#HIGHEST_PRECEDENCE} for the reason {@link AttachmentExceptionHandler} documents:
 * handler methods are matched by exception specificity only <em>within</em> one advice, and across
 * advices Spring takes the first bean that has any matching method.
 * <p>
 * The event is logged here rather than left to the filter chain's handler, which never runs for
 * these: without it a role denial reaches no security log at all, and the fail2ban jails read that
 * log.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class AccessDeniedExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AccessDeniedExceptionHandler.class);

    private final SecurityEventLogger securityEventLogger;

    public AccessDeniedExceptionHandler(SecurityEventLogger securityEventLogger) {
        this.securityEventLogger = securityEventLogger;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Void> handleAccessDenied(AccessDeniedException accessDeniedException,
                                                   HttpServletRequest request) {
        log.debug("Access denied: {}", accessDeniedException.getMessage());
        securityEventLogger.log(AUTHORIZATION_DENIED, request, SC_FORBIDDEN, SecurityEventReason.INSUFFICIENT_AUTHORITY);

        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Method security answers a missing authentication with this rather than an
     * {@link AccessDeniedException} — unrelated types, so handling only the latter would let an
     * unauthenticated caller through to the catch-all and out as a {@code 200}.
     */
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<Void> handleMissingCredentials(
            AuthenticationCredentialsNotFoundException authenticationCredentialsNotFoundException,
            HttpServletRequest request) {
        log.debug("No authenticated caller: {}", authenticationCredentialsNotFoundException.getMessage());
        securityEventLogger.log(AUTHORIZATION_DENIED, request, SC_UNAUTHORIZED, SecurityEventReason.MISSING_TOKEN);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
