package com.solesonic.security;

import com.solesonic.config.RequestLoggingFilter;
import com.solesonic.model.security.SecurityEventReason;
import com.solesonic.service.security.SecurityEventLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Locale;

import static com.solesonic.model.security.SecurityEvent.AUTHENTICATION_FAILURE;
import static com.solesonic.model.security.SecurityEvent.AUTHORIZATION_DENIED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile({"prod", "prod-nginx"})
public class SecurityConfig {
    public static final String BROKER_ATLASSIAN_TOKEN = "/broker/atlassian/token";

    public static final String ROLE = "ROLE_";
    public static final String ROLES = "roles";

    private static final String EXPIRED_MARKER = "expired";
    private static final String SIGNATURE_MARKER = "signature";
    private static final String ISSUER_MARKER = "the iss claim";
    private static final String AUDIENCE_MARKER = "the aud claim";

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    private final JwtUserRequestFilter jwtUserRequestFilter;
    private final RequestLoggingFilter requestLoggingFilter;
    private final SecurityEventLogger securityEventLogger;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public SecurityConfig(JwtUserRequestFilter jwtUserRequestFilter,
                          RequestLoggingFilter requestLoggingFilter,
                          SecurityEventLogger securityEventLogger) {
        this.jwtUserRequestFilter = jwtUserRequestFilter;
        this.requestLoggingFilter = requestLoggingFilter;
        this.securityEventLogger = securityEventLogger;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Bean
    @Profile({"prod", "prod-nginx"})
    public SecurityFilterChain prodSecurityFilterChain(HttpSecurity http) {
        AuthenticationEntryPoint authenticationEntryPoint = authenticationEntryPoint();

        http.exceptionHandling(config -> config.accessDeniedHandler(accessDeniedHandler()));
        http.exceptionHandling(config -> config.authenticationEntryPoint(authenticationEntryPoint));

        http
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        // A bearer token that fails to decode is answered by this filter's own
                        // entry point, not by the one ExceptionTranslationFilter holds. Both have
                        // to be ours, or an expired token produces no security event at all.
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.decoder(jwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        ));

        http.addFilterBefore(requestLoggingFilter, UsernamePasswordAuthenticationFilter.class);

        // Before AuthorizationFilter rather than immediately after BearerTokenAuthenticationFilter:
        // that places it downstream of ExceptionTranslationFilter, so its refusal of an
        // unauthenticated request is translated into a 401 through the entry point instead of
        // escaping the chain as an untranslated 500 that no security event ever sees.
        http.addFilterBefore(jwtUserRequestFilter, AuthorizationFilter.class);

        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);

        return http.build();

    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix(ROLE);
        grantedAuthoritiesConverter.setAuthoritiesClaimName(ROLES);

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);

        return jwtAuthenticationConverter;
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authenticationException) -> {
            securityEventLogger.log(AUTHENTICATION_FAILURE, request, SC_UNAUTHORIZED, reason(authenticationException));

            response.setContentType(APPLICATION_JSON_VALUE);
            response.setStatus(SC_UNAUTHORIZED);
        };
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, _) -> {
            securityEventLogger.log(AUTHORIZATION_DENIED, request, SC_FORBIDDEN, SecurityEventReason.INSUFFICIENT_AUTHORITY);

            response.sendError(SC_FORBIDDEN, "Access Denied");
        };
    }

    /**
     * Maps the failure to the closed reason enum in one place. The JWT validators cannot see the
     * request, so the entry point reads it back off the exception instead — an
     * {@link OAuth2AuthenticationException} carries an {@code OAuth2Error} whose description
     * distinguishes the cases, which keeps the validators themselves pure.
     */
    private static SecurityEventReason reason(AuthenticationException authenticationException) {
        if (!(authenticationException instanceof OAuth2AuthenticationException oauth2AuthenticationException)) {
            // Nothing decoded a token at all: the request arrived without one.
            return SecurityEventReason.MISSING_TOKEN;
        }

        String description = oauth2AuthenticationException.getError().getDescription();

        if (description == null) {
            return SecurityEventReason.MALFORMED_TOKEN;
        }

        String lowerCaseDescription = description.toLowerCase(Locale.ROOT);

        if (lowerCaseDescription.contains(EXPIRED_MARKER)) {
            return SecurityEventReason.EXPIRED_TOKEN;
        }

        if (lowerCaseDescription.contains(SIGNATURE_MARKER)) {
            return SecurityEventReason.INVALID_SIGNATURE;
        }

        if (lowerCaseDescription.contains(ISSUER_MARKER)) {
            return SecurityEventReason.WRONG_ISSUER;
        }

        if (lowerCaseDescription.contains(AUDIENCE_MARKER)) {
            return SecurityEventReason.WRONG_AUDIENCE;
        }

        return SecurityEventReason.MALFORMED_TOKEN;
    }
}
