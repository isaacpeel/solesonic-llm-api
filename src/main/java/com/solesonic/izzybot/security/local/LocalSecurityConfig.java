package com.solesonic.izzybot.security.local;

import com.solesonic.izzybot.config.RequestLoggingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;

@Configuration
@EnableWebSecurity
@Profile({"test", "local"})
public class LocalSecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(LocalSecurityConfig.class);

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    private final LocalJwtUserRequestFilter jwtUserRequestFilter;
    private final RequestLoggingFilter requestLoggingFilter;

    public LocalSecurityConfig(LocalJwtUserRequestFilter jwtUserRequestFilter, RequestLoggingFilter requestLoggingFilter) {
        this.jwtUserRequestFilter = jwtUserRequestFilter;
        this.requestLoggingFilter = requestLoggingFilter;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Bean
    @Profile({"test", "local"})
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http.oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder())));

        http.addFilterBefore(requestLoggingFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(jwtUserRequestFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            log.warn("{} - Access Denied: {} trying to access {} from IP: {}", SC_FORBIDDEN, request.getRemoteAddr(), request.getRequestURI(), request.getRemoteAddr());
            response.sendError(SC_FORBIDDEN, "Access Denied");
        };
    }
}
