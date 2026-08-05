package com.solesonic.util.logging;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Makes attacker-influenced request material safe to write to a log file.
 * <p>
 * Two separate jobs, because the two log streams have different rules. {@link #redactQuery(String)}
 * keeps the query string readable in the application log while replacing the <em>values</em> of
 * sensitive parameters. {@link #sanitizePath(String)} and {@link #sanitizeMethod(String)} strip
 * everything that could let a request write a line of its own choosing into the security log and
 * which therefore turns a log-injection hole into a remote-controlled firewall.
 */
public final class Redactor {

    /**
     * Parameter names whose values never reach a log file. Matched exactly and case-insensitively:
     * the previous implementation used a substring replace, so {@code code} also matched inside
     * {@code qrcode}, and it replaced the parameter name rather than the value.
     */
    private static final Set<String> DENYLIST = Set.of(
            "code",
            "state",
            "token",
            "access_token",
            "id_token",
            "refresh_token",
            "client_secret",
            "password",
            "api_key",
            "secret");

    /**
     * An allowlist, not a denylist. A denylist of control characters misses the next encoding;
     * this cannot emit a newline, a quote, or a space no matter what arrives.
     */
    private static final Pattern DISALLOWED_PATH_CHARACTERS = Pattern.compile("[^A-Za-z0-9/_.:@-]");

    private static final Pattern ALLOWED_METHOD = Pattern.compile("[A-Z]{3,10}");

    private static final int MAX_PATH_LENGTH = 120;
    private static final String ABSENT = "-";
    private static final String REDACTED = "*****";
    private static final String UNPARSEABLE_QUERY = "unparseable=" + REDACTED;

    private Redactor() {
    }

    /**
     * Replaces the values of sensitive query parameters, leaving the rest readable.
     */
    public static String redactQuery(String queryString) {
        if (StringUtils.isEmpty(queryString)) {
            return queryString;
        }

        MultiValueMap<String, String> parameters;

        try {
            parameters = UriComponentsBuilder.newInstance()
                    .query(queryString)
                    .build()
                    .getQueryParams();
        } catch (IllegalArgumentException illegalArgumentException) {
            // The query string is attacker-controlled, so a parse failure is an expected input,
            // not a bug. Log nothing of it rather than falling back to the raw text.
            return UNPARSEABLE_QUERY;
        }

        return parameters.entrySet().stream()
                .map(parameter -> parameterEntry(parameter.getKey(), parameter.getValue()))
                .collect(Collectors.joining("&"));
    }

    /**
     * The one attacker-influenced field the security log keeps. Anything outside the allowlist is
     * dropped and the result is truncated, so a request cannot forge a log line.
     */
    public static String sanitizePath(String path) {
        if (StringUtils.isEmpty(path)) {
            return ABSENT;
        }

        String cleaned = DISALLOWED_PATH_CHARACTERS.matcher(path).replaceAll("");

        if (cleaned.isEmpty()) {
            return ABSENT;
        }

        return cleaned.length() > MAX_PATH_LENGTH ? cleaned.substring(0, MAX_PATH_LENGTH) : cleaned;
    }

    /**
     * Tomcat rejects a request line with a strange method, so this is defence in depth — but the
     * method reaches the same log line as the path and costs three lines to make safe.
     */
    public static String sanitizeMethod(String method) {
        if (StringUtils.isEmpty(method) || !ALLOWED_METHOD.matcher(method).matches()) {
            return ABSENT;
        }

        return method;
    }

    private static String parameterEntry(String name, List<String> values) {
        if (DENYLIST.contains(name.toLowerCase(Locale.ROOT))) {
            return name + "=" + REDACTED;
        }

        if (values == null || values.isEmpty()) {
            return name;
        }

        return values.stream()
                .map(value -> value == null ? name : name + "=" + value)
                .collect(Collectors.joining("&"));
    }
}
