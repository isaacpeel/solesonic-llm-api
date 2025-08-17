package com.solesonic.config;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A CORS processor that logs detailed diagnostics for CORS requests and preflight checks.
 * It helps explain why Access-Control-Allow-Origin may be missing.
 */
public class LoggingCorsProcessor extends DefaultCorsProcessor {
    private static final Logger log = LoggerFactory.getLogger(LoggingCorsProcessor.class);

    @Override
    public boolean processRequest(CorsConfiguration config,
                                  @NonNull HttpServletRequest request,
                                  @NonNull HttpServletResponse response) throws IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String method = request.getMethod();
        String requestUri = request.getRequestURI();
        String acrMethod = request.getHeader("Access-Control-Request-Method");
        String acrHeaders = request.getHeader("Access-Control-Request-Headers");

        if (origin == null) {
            // Not a CORS request; nothing to log loudly.
            return super.processRequest(config, request, response);
        }

        // Snapshot current CORS configuration details for logging
        List<String> allowedOrigins = config != null && config.getAllowedOrigins() != null
                ? config.getAllowedOrigins() : Collections.emptyList();
        List<String> allowedOriginPatterns = config != null && config.getAllowedOriginPatterns() != null
                ? config.getAllowedOriginPatterns() : Collections.emptyList();
        List<String> allowedMethods = config != null && config.getAllowedMethods() != null
                ? config.getAllowedMethods() : Collections.emptyList();
        List<String> allowedHeaders = config != null && config.getAllowedHeaders() != null
                ? config.getAllowedHeaders() : Collections.emptyList();

        log.info("[CORS] Incoming {} {} | Origin={} | ACR-Method={} | ACR-Headers={}",
                method, requestUri, origin, acrMethod, acrHeaders);

        if (config == null) {
            log.warn("[CORS] No CorsConfiguration matched for path: {}. CORS will not be applied.", requestUri);
            return super.processRequest(null, request, response);
        }

        // Compute diagnostics prior to delegating to DefaultCorsProcessor
        boolean originAllowed = isOriginAllowed(origin, allowedOrigins, allowedOriginPatterns);
        boolean methodAllowed = true; // default true for actual request; evaluated for preflight below
        boolean headersAllowed = true; // evaluated for preflight below

        if (isPreflight(method)) {
            if (acrMethod != null) {
                methodAllowed = isMethodAllowed(acrMethod, allowedMethods);
            }
            if (StringUtils.isNotBlank(acrHeaders)) {
                List<String> requestedHeaders = Arrays.stream(acrHeaders.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                headersAllowed = areHeadersAllowed(requestedHeaders, allowedHeaders);
            }
        }

        boolean handled = super.processRequest(config, request, response);

        String acao = response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        if (acao == null) {
            // Provide explicit reasons to aid debugging
            if (!originAllowed) {
                log.warn("[CORS][BLOCKED] Origin not allowed. Origin={} | allowedOrigins={} | allowedOriginPatterns={}",
                        origin, allowedOrigins, allowedOriginPatterns);
            } else if (isPreflight(method) && !methodAllowed) {
                log.warn("[CORS][BLOCKED] Preflight method not allowed. ACR-Method={} | allowedMethods={}",
                        acrMethod, allowedMethods);
            } else if (isPreflight(method) && !headersAllowed) {
                log.warn("[CORS][BLOCKED] One or more requested headers are not allowed. ACR-Headers={} | allowedHeaders={}",
                        acrHeaders, allowedHeaders);
            } else {
                log.warn("[CORS][BLOCKED] ACAO header not set for {} {}. Possible causes: credentials disallowed, missing headers, or internal rejection.",
                        method, requestUri);
            }
        } else {
            log.info("[CORS][OK] ACAO='{}' set for {} {}", acao, method, requestUri);
        }

        return handled;
    }

    private boolean isPreflight(String method) {
        return "OPTIONS".equalsIgnoreCase(method);
    }

    private boolean isOriginAllowed(String origin, List<String> allowedOrigins, List<String> allowedOriginPatterns) {
        if ("*".equals(origin)) return true;
        // Exact origins
        for (String allowed : allowedOrigins) {
            if (origin.equals(allowed)) return true;
        }
        // Patterns (Spring style with * wildcard)
        for (String pattern : allowedOriginPatterns) {
            if (matchesPattern(origin, pattern)) return true;
        }
        return false;
    }

    private boolean isMethodAllowed(String acrMethod, List<String> allowedMethods) {
        if (allowedMethods == null || allowedMethods.isEmpty()) return false;
        if (allowedMethods.contains("*")) return true;
        for (String m : allowedMethods) {
            if (acrMethod.equalsIgnoreCase(m)) return true;
        }
        return false;
    }

    private boolean areHeadersAllowed(List<String> requested, List<String> allowedHeaders) {
        if (allowedHeaders == null || allowedHeaders.isEmpty()) return requested.isEmpty();
        if (allowedHeaders.contains("*")) return true;
        Set<String> allowed = allowedHeaders.stream().map(String::toLowerCase).collect(Collectors.toSet());
        for (String h : requested) {
            if (!allowed.contains(h.toLowerCase())) return false;
        }
        return true;
    }

    // Simple wildcard match where pattern may contain one or more '*' tokens
    private boolean matchesPattern(String origin, String pattern) {
        if (pattern == null) return false;
        if ("*".equals(pattern)) return true;
        // Escape regex special chars except '*', then replace '*' with '.*'
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return origin.matches(regex);
    }
}
