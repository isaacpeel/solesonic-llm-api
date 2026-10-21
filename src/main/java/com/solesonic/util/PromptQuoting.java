package com.solesonic.util;

import java.util.Objects;

/**
 * The one rule for putting untrusted text into a prompt: a single quoted string with every line
 * break escaped, so nothing inside it — an email, a web page, a memory — can start a line of its own
 * that looks like part of the prompt's structure.
 */
public final class PromptQuoting {

    private PromptQuoting() {
    }

    public static String quote(String text) {
        String escaped = Objects.toString(text, "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u000B", "\\u000B")
                .replace("\f", "\\f")
                .replace("\u0085", "\\u0085")
                .replace(" ", "\\u2028")
                .replace(" ", "\\u2029");

        return '"' + escaped + '"';
    }
}
