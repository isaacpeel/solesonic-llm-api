package com.solesonic.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.springframework.http.HttpMethod.*;

@Configuration
public class CorsGlobalConfig {
    private static final Logger log = LoggerFactory.getLogger(CorsGlobalConfig.class);

    @Value("${cors.allowed.origins}")
    private String[] allowedOrigins;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsFilter globalCorsFilter() {
        // Prepare and trim origins to avoid whitespace mismatches
        List<String> rawOrigins = List.of(allowedOrigins);
        List<String> trimmedOrigins = rawOrigins.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));

        if (!rawOrigins.equals(trimmedOrigins)) {
            log.info("[CORS] Trimming cors.allowed.origins. Raw={}, Trimmed={}", rawOrigins, trimmedOrigins);
        }

        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOrigins(trimmedOrigins);
        corsConfiguration.setAllowedMethods(List.of(GET.name(), POST.name(), PUT.name(), DELETE.name(), OPTIONS.name()));
        corsConfiguration.setAllowedHeaders(List.of("*"));
        corsConfiguration.setAllowCredentials(false);
        corsConfiguration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);

        CorsFilter corsFilter = new CorsFilter(source);
        corsFilter.setCorsProcessor(new LoggingCorsProcessor());

        log.info("[CORS] Global CorsFilter configured with allowed origins: {}", String.join(", ", trimmedOrigins));
        return corsFilter;
    }
}
