package com.solesonic.service.security;

import com.solesonic.model.security.SecurityEventReason;
import com.solesonic.scope.UserRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.solesonic.model.security.SecurityEvent.AUTHORIZATION_DENIED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;

/**
 * Confirms a {@code {userId}} path segment names the caller and no one else.
 * <p>
 * The subject comes from {@link UserRequestContext#getUserId()} — never the path, a header, or the
 * body — because that is the one value every profile's filter chain has already resolved from a
 * JWT. Under {@code test}/{@code local} it is {@code LocalJwtUserRequestFilter}'s fallback to the
 * first stored user rather than a real subject, and this check is enforced against it the same as
 * everywhere else: local dev is scoped to that one user, rather than silently bypassing the check
 * for every other id the way an unauthenticated request would.
 */
@Service
public class ResourceOwnershipService {
    private final UserRequestContext userRequestContext;
    private final SecurityEventLogger securityEventLogger;

    public ResourceOwnershipService(UserRequestContext userRequestContext, SecurityEventLogger securityEventLogger) {
        this.userRequestContext = userRequestContext;
        this.securityEventLogger = securityEventLogger;
    }

    public boolean isOwner(UUID pathUserId, HttpServletRequest request) {
        if (pathUserId.equals(userRequestContext.getUserId())) {
            return true;
        }

        securityEventLogger.log(AUTHORIZATION_DENIED, request, SC_FORBIDDEN, SecurityEventReason.WRONG_SUBJECT);

        return false;
    }
}
