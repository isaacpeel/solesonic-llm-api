package com.solesonic.service.interest;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One spelling per article, so the same story reached through a tracking link, a fragment or a
 * trailing slash is seen once. Anything that is not {@code http} or {@code https} is refused: a
 * search result is untrusted, and only a web link may ever be rendered.
 */
public final class NewsUrlNormalizer {
    private static final Set<String> SCHEMES = Set.of("http", "https");
    private static final Set<String> TRACKING_PARAMETERS = Set.of("fbclid", "gclid");
    private static final String TRACKING_PREFIX = "utm_";
    private static final Pattern NUMERIC_LABEL = Pattern.compile("[0-9]+|0x[0-9a-f]*");
    private static final List<String> INTERNAL_SUFFIXES = List.of(".localhost", ".local", ".internal", ".lan", ".home.arpa");

    private NewsUrlNormalizer() {
    }

    public static Optional<String> normalize(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }

        URI uri;

        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);

        if (scheme == null || !SCHEMES.contains(scheme) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1) {
            return Optional.empty();
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);

        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }

        if (!isPublicName(host)) {
            return Optional.empty();
        }

        StringBuilder normalized = new StringBuilder(scheme)
                .append("://")
                .append(host);

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();

        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        normalized.append(path);

        String query = query(uri.getRawQuery());

        if (!query.isEmpty()) {
            normalized.append('?').append(query);
        }

        return Optional.of(normalized.toString());
    }

    /**
     * The SHA-256 of a normalized URL, as 64 lower-case hex characters: all an agent keeps of an
     * article it has seen.
     */
    public static String hash(String normalizedUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedUrl.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * The host a reader recognises, without a leading {@code www.}.
     */
    public static String host(String normalizedUrl) {
        String host = URI.create(normalizedUrl).getHost();

        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /**
     * A news article lives on a public domain name. An IP literal in any spelling a resolver accepts
     * (a numeric or hex last label), {@code localhost} or an internal suffix is refused before the URL can be handed to the extraction tool, which fetches it: a
     * poisoned search result must not point the server at its own network. The MCP server keeps its own
     * resolve-time checks, since a public name can still resolve inward.
     */
    private static boolean isPublicName(String host) {
        if (host.startsWith("[") || !host.contains(".")) {
            return false;
        }

        if (NUMERIC_LABEL.matcher(host.substring(host.lastIndexOf('.') + 1)).matches()) {
            return false;
        }

        return INTERNAL_SUFFIXES.stream().noneMatch(host::endsWith);
    }

    private static String query(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }

        return Arrays.stream(rawQuery.split("&"))
                .filter(parameter -> !parameter.isEmpty())
                .filter(parameter -> !isTracking(parameter))
                .collect(Collectors.joining("&"));
    }

    private static boolean isTracking(String parameter) {
        String name = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);

        return name.startsWith(TRACKING_PREFIX) || TRACKING_PARAMETERS.contains(name);
    }
}
