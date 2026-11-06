package com.solesonic.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The inline markers that hide part of a shared session's text from some participants:
 * {@code [[hidden:Sam,Jo]]} … {@code [[/hidden]]}. Clients enforce them; the API only writes them
 * around a typed message the agent asked to hide, and removes any a participant typed, so nobody can
 * forge or close one.
 */
public final class HiddenText {

    private static final Pattern MARKER = Pattern.compile("\\[\\[\\s*(?:/\\s*hidden|hidden\\s*:[^]]*)\\s*]]", Pattern.CASE_INSENSITIVE);

    private HiddenText() {
    }

    public static String wrap(List<String> names, String text) {
        return "[[hidden:" + String.join(",", names) + "]]\n" + text + "\n[[/hidden]]";
    }

    /**
     * Repeats until nothing matches, so removing one marker cannot join its neighbours into another.
     */
    public static String strip(String text) {
        String stripped = text;
        String previous;

        do {
            previous = stripped;
            stripped = MARKER.matcher(previous).replaceAll("");
        } while (!stripped.equals(previous));

        return stripped;
    }
}
