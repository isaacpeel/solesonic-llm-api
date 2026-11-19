package com.solesonic.service.agentupdate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans the markdown an agent delivers before it is stored as a chat message. An agent writes its
 * links from what it stored (a careers page), but the text around them came from the open web, so
 * the only links kept are inline {@code [text](https://...)} links; every other link form, image,
 * raw HTML and bare or non-https URL is removed. Plain markdown (headings, lists, emphasis) stays.
 */
public final class AgentMarkdownSanitizer {
    private static final Pattern IMAGES = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern INLINE_LINKS = Pattern.compile("\\[([^]\\n]*)]\\(([^)\\s]*)\\)");
    private static final Pattern SAFE_URL = Pattern.compile("https://[A-Za-z0-9.-]+(?::\\d{1,5})?(?:[/?#][^\\s()<>\"'`]*)?");
    private static final Pattern REFERENCE_DEFINITIONS = Pattern.compile("(?m)^[ \\t]*\\[[^]]+]:.*$");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern BARE_URLS = Pattern.compile("(?i)(?:[a-z][a-z0-9+.-]*://|//|www\\.)\\S+");
    private static final Pattern URL_SCHEMES = Pattern.compile("(?i)\\b(?:javascript|vbscript|data|file|mailto|tel):\\S*");
    private static final Pattern EMAIL_ADDRESSES = Pattern.compile("\\S+@\\S+\\.\\S+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\u0000(\\d+)\u0000");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private AgentMarkdownSanitizer() {
    }

    public static String markdown(String text) {
        if (text == null) {
            return "";
        }

        String sanitized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        sanitized = IMAGES.matcher(sanitized).replaceAll("");

        //Kept links are set aside, so the bare-URL rules below cannot eat their targets.
        List<String> links = new ArrayList<>();
        Matcher matcher = INLINE_LINKS.matcher(sanitized);
        StringBuilder withPlaceholders = new StringBuilder();

        while (matcher.find()) {
            String label = plain(matcher.group(1));
            String url = matcher.group(2);
            String replacement;

            if (SAFE_URL.matcher(url).matches() && !label.isBlank()) {
                links.add("[" + label.replace("[", "").replace("]", "") + "](" + url + ")");
                replacement = "\u0000" + (links.size() - 1) + "\u0000";
            } else {
                replacement = label;
            }

            matcher.appendReplacement(withPlaceholders, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(withPlaceholders);
        sanitized = withPlaceholders.toString();

        sanitized = REFERENCE_DEFINITIONS.matcher(sanitized).replaceAll("");
        sanitized = TAGS.matcher(sanitized).replaceAll("");
        sanitized = BARE_URLS.matcher(sanitized).replaceAll("");
        sanitized = URL_SCHEMES.matcher(sanitized).replaceAll("");
        sanitized = EMAIL_ADDRESSES.matcher(sanitized).replaceAll("");
        sanitized = PLACEHOLDER.matcher(sanitized).replaceAll(match -> Matcher.quoteReplacement(links.get(Integer.parseInt(match.group(1)))));
        sanitized = EXTRA_BLANK_LINES.matcher(sanitized).replaceAll("\n\n");

        return sanitized.trim();
    }

    /**
     * A chat title: one line of plain text, no markup, at most {@code maxCharacters}.
     */
    public static String title(String text, int maxCharacters) {
        String plain = plain(text == null ? "" : text).replaceAll("[*_`#>\\[\\]]", "");
        plain = WHITESPACE.matcher(plain).replaceAll(" ").trim();

        return plain.length() <= maxCharacters ? plain : plain.substring(0, maxCharacters - 1).trim() + "…";
    }

    private static String plain(String text) {
        String plain = TAGS.matcher(text).replaceAll("");
        plain = BARE_URLS.matcher(plain).replaceAll("");
        return URL_SCHEMES.matcher(plain).replaceAll("");
    }
}
