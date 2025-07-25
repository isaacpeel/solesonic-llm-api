package com.solesonic.izzybot.security;

import com.solesonic.izzybot.config.RequestLoggingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@Profile({"prod"})
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    private final JwtUserRequestFilter jwtUserRequestFilter;
    private final RequestLoggingFilter requestLoggingFilter;

    public SecurityConfig(JwtUserRequestFilter jwtUserRequestFilter, RequestLoggingFilter requestLoggingFilter) {
        this.jwtUserRequestFilter = jwtUserRequestFilter;
        this.requestLoggingFilter = requestLoggingFilter;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Bean
    @Profile({"prod"})
    public SecurityFilterChain prodSecurityFilterChain(HttpSecurity http) throws Exception {
        http.exceptionHandling(config -> config.accessDeniedHandler(accessDeniedHandler()));
        http.exceptionHandling(config -> config.authenticationEntryPoint(authenticationEntryPoint()));

        http
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder())));

        http.addFilterBefore(requestLoggingFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(jwtUserRequestFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            log.warn("{} - Unauthorized access attempt: remote addr: {} {} - {}", SC_UNAUTHORIZED, request.getRemoteAddr(), request.getMethod(), request.getRequestURI());

            response.setContentType(APPLICATION_JSON_VALUE);
            response.setStatus(SC_UNAUTHORIZED);
        };
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            log.warn("{} - Access Denied: {} trying to access {} from IP: {}", SC_FORBIDDEN, request.getRemoteAddr(), request.getRequestURI(), request.getRemoteAddr());
            response.sendError(SC_FORBIDDEN, "Access Denied");
        };
    }
}
