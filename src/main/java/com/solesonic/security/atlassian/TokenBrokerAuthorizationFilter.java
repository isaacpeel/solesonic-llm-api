package com.solesonic.security.atlassian;

import com.solesonic.config.atlassian.TokenBrokerProperties;
import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import static io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider.APPLICATION_JSON;

@Component
public class TokenBrokerAuthorizationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenBrokerAuthorizationFilter.class);
    public static final String SCOPE = "scope";
    public static final String SCP = "scp";
    public static final String AUD = "aud";

    private final TokenBrokerProperties properties;

    public TokenBrokerAuthorizationFilter(TokenBrokerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @Nonnull HttpServletResponse response,
                                    @Nonnull FilterChain filterChain) throws ServletException, IOException {

        String requestPath = request.getRequestURI();

        // Only apply to internal token broker endpoints
        if (!requestPath.contains("/internal/atlassian/token")) {
            filterChain.doFilter(request, response);
            return;
        }

        log.debug("Token broker authorization check for path: {}", requestPath);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null) {
            log.warn("No authentication found for token broker request");
            sendUnauthorizedResponse(response, "Authentication required");
            return;
        }

        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            log.warn("Invalid authentication type for token broker: {}", authentication.getClass().getSimpleName());
            sendUnauthorizedResponse(response, "JWT authentication required");
            return;
        }

        Jwt jwt = jwtAuth.getToken();

        // Check required scope
        if (!hasRequiredScope(jwt)) {
            log.warn("Missing required scope '{}' for token broker access", properties.getRequiredScope());
            sendForbiddenResponse(response, "Missing required scope: " + properties.getRequiredScope());
            return;
        }

        // Check required audience
        if (!hasRequiredAudience(jwt)) {
            log.warn("Missing required audience '{}' for token broker access", properties.getRequiredAudience());
            sendForbiddenResponse(response, "Missing required audience: " + properties.getRequiredAudience());
            return;
        }

        log.debug("Token broker authorization successful for subject: {}", jwt.getSubject());
        filterChain.doFilter(request, response);
    }

    private boolean hasRequiredScope(Jwt jwt) {
        String requiredScope = properties.getRequiredScope();
        if (requiredScope == null || requiredScope.trim().isEmpty()) {
            return true; // No scope requirement configured
        }
        // Check 'scope' claim (space-separated string)
        String scopeClaim = jwt.getClaimAsString(SCOPE);
        if (scopeClaim != null) {
            String[] scopes = scopeClaim.split("\\s+");
            for (String scope : scopes) {
                if (requiredScope.equals(scope.trim())) {
                    return true;
                }
            }
        }

        // Check 'scp' claim (array or space-separated string)
        Object scpClaim = jwt.getClaim(SCP);
        if (scpClaim instanceof List<?> scopeList) {
            return scopeList.contains(requiredScope);
        } else if (scpClaim instanceof String scpString) {
            String[] scopes = scpString.split("\\s+");

            for (String scope : scopes) {
                if (requiredScope.equals(scope.trim())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean hasRequiredAudience(Jwt jwt) {
        String requiredAudience = properties.getRequiredAudience();
        if (requiredAudience == null || requiredAudience.trim().isEmpty()) {
            return true; // No audience requirement configured
        }

        // Check 'aud' claim
        Object audClaim = jwt.getClaim(AUD);
        if (audClaim instanceof List<?> audienceList) {
            return audienceList.contains(requiredAudience);
        } else if (audClaim instanceof String audString) {
            return requiredAudience.equals(audString);
        }

        return false;
    }

    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(APPLICATION_JSON);

        response.getWriter().write(String.format(
                "{\"error\":\"UNAUTHORIZED\",\"message\":\"%s\"}",
                message));
    }

    private void sendForbiddenResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(APPLICATION_JSON);
        response.getWriter().write(String.format(
                "{\"error\":\"FORBIDDEN\",\"message\":\"%s\"}",
                message));
    }
}