package com.solesonic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.http.HttpHeaders.*;
import static org.springframework.http.HttpMethod.*;

@Configuration
public class WebConfig {
    @Value("${cors.allowed.origins}")
    private String[] allowedOrigins;

    @Bean
    public WebMvcConfigurer webConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(allowedOrigins)
                        .allowedMethods(GET.name(), POST.name(), PUT.name(), DELETE.name(), OPTIONS.name())
                        .allowedHeaders(AUTHORIZATION, CONTENT_TYPE, ACCEPT_ENCODING)
                        .allowCredentials(false);
            }
        };
    }
}
