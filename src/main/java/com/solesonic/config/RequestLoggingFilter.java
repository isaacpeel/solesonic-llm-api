package com.solesonic.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to log all HTTP requests, their headers, and authentication status.
 * This filter logs whether requests are authenticated or not.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    public static final String ANONYMOUS_USER = "anonymousUser";

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Log request details
        String method = request.getMethod();
        String requestURI = request.getRequestURI();
        String queryString = request.getQueryString();
        String fullUrl = StringUtils.isEmpty(queryString) ? requestURI : requestURI + "?" + queryString;

        // Check authentication status
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        boolean isAuthenticated = authentication != null && authentication.isAuthenticated() && 
                                 !authentication.getAuthorities().isEmpty() && 
                                 !ANONYMOUS_USER.equals(authentication.getPrincipal());

        log.info("Request: {} {} - Authenticated: {}", method, fullUrl, isAuthenticated);

        filterChain.doFilter(request, response);
    }
}
