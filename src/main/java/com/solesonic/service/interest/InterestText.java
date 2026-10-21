package com.solesonic.service.interest;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one rule for text an interest stores, whether the model proposed it or the user edited it: a
 * single trimmed line with no control characters, and a topic key reduced to {@code [a-z0-9.-]}.
 */
public final class InterestText {
    public static final int MAX_LABEL_CHARACTERS = 120;
    public static final int MAX_QUERY_CHARACTERS = 400;
    public static final int MAX_RATIONALE_CHARACTERS = 500;
    public static final int MAX_TOPIC_KEY_CHARACTERS = 128;

    private static final Pattern LINE_BREAKS_AND_CONTROLS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[\\s_/]+");
    private static final Pattern NOT_KEY_CHARACTERS = Pattern.compile("[^a-z0-9.-]");
    private static final Pattern REPEATED_HYPHENS = Pattern.compile("-{2,}");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[.-]+|[.-]+$");

    private InterestText() {
    }

    /**
     * The text on one line, or null when nothing is left of it.
     */
    public static String singleLine(String text) {
        if (text == null) {
            return null;
        }

        String cleaned = WHITESPACE.matcher(LINE_BREAKS_AND_CONTROLS.matcher(text).replaceAll(" ")).replaceAll(" ").trim();

        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * {@link #singleLine} cut to {@code maxCharacters}, or null when nothing is left of it.
     */
    public static String singleLine(String text, int maxCharacters) {
        String line = singleLine(text);

        if (line == null || line.length() <= maxCharacters) {
            return line;
        }

        return line.substring(0, maxCharacters).trim();
    }

    /**
     * A topic key as stored, so two spellings of the same topic compare equal, or null when nothing is
     * left of it.
     */
    public static String topicKey(String key) {
        if (key == null) {
            return null;
        }

        String lowerCase = KEY_SEPARATORS.matcher(key.trim().toLowerCase(Locale.ROOT)).replaceAll("-");
        String allowed = REPEATED_HYPHENS.matcher(NOT_KEY_CHARACTERS.matcher(lowerCase).replaceAll("")).replaceAll("-");
        String trimmed = EDGE_PUNCTUATION.matcher(allowed).replaceAll("");

        if (trimmed.length() > MAX_TOPIC_KEY_CHARACTERS) {
            trimmed = EDGE_PUNCTUATION.matcher(trimmed.substring(0, MAX_TOPIC_KEY_CHARACTERS)).replaceAll("");
        }

        return trimmed.isEmpty() ? null : trimmed;
    }
}
