package com.solesonic.service.interest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips from model-written text whatever a markdown renderer could turn into a link with no markup at
 * all — URLs, {@code www.} hosts, protocol-relative and scheme references, email addresses — along with
 * the obvious link, image and tag syntax. It is the first of two layers: a regex cannot recognise every
 * way CommonMark spells a link, so {@link NewsUpdateRenderer} then escapes all punctuation in what is
 * left, and that escaping is what guarantees the only links in an update are the ones it builds.
 */
public final class ModelTextSanitizer {
    private static final Pattern IMAGES = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern INLINE_LINKS = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern REFERENCE_DEFINITIONS = Pattern.compile("(?m)^[ \\t]*\\[[^]]+]:.*$");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern BARE_URLS = Pattern.compile("(?i)(?:[a-z][a-z0-9+.-]*://|//|www\\.)\\S+");
    private static final Pattern URL_SCHEMES = Pattern.compile("(?i)\\b(?:javascript|vbscript|data|file|mailto|tel):\\S*");
    private static final Pattern EMAIL_ADDRESSES = Pattern.compile("\\S+@\\S+\\.\\S+");
    private static final Pattern HEADING_MARKERS = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*");
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t]+$");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private ModelTextSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }

        String sanitized = text.replace("\r\n", "\n").replace('\r', '\n');

        sanitized = IMAGES.matcher(sanitized).replaceAll("");
        sanitized = INLINE_LINKS.matcher(sanitized).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        sanitized = REFERENCE_DEFINITIONS.matcher(sanitized).replaceAll("");
        sanitized = TAGS.matcher(sanitized).replaceAll("");
        sanitized = BARE_URLS.matcher(sanitized).replaceAll("");
        sanitized = URL_SCHEMES.matcher(sanitized).replaceAll("");
        sanitized = EMAIL_ADDRESSES.matcher(sanitized).replaceAll("");
        sanitized = HEADING_MARKERS.matcher(sanitized).replaceAll("");
        sanitized = TRAILING_SPACES.matcher(sanitized).replaceAll("");
        sanitized = EXTRA_BLANK_LINES.matcher(sanitized).replaceAll("\n\n");

        return sanitized.trim();
    }
}
