package com.solesonic.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        // Log request details
        String method = request.getMethod();
        String requestURI = request.getRequestURI();
        String queryString = request.getQueryString();

        String redactedQueryString = redact(queryString, "code");

        String fullUrl = StringUtils.isEmpty(redactedQueryString) ? requestURI : requestURI + "?" + redactedQueryString;

        log.info("Request: {} {}", method, fullUrl);

        filterChain.doFilter(request, response);
    }

    private String redact(String queryString, String... toRedact) {
        if (StringUtils.isEmpty(queryString)) {
            return queryString;
        }

        for (String key : toRedact) {
            queryString = queryString.replaceFirst(key, "*****");
        }

        return queryString;
    }
}
