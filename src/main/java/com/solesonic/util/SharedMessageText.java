package com.solesonic.util;

import org.apache.commons.lang3.StringUtils;

/**
 * How a shared conversation's user message reaches the model: the sender's name, a colon, then their
 * words. The same text is built for the live turn and when history is replayed, so the model sees one
 * shape. Never stored.
 */
public final class SharedMessageText {

    /**
     * What stands for the words of a message that carried only attachments or a command.
     */
    static final String NO_TEXT = "(sent without any text)";

    private SharedMessageText() {
    }

    /**
     * The words themselves, or the no-text phrase when there are none. For the routes that hand the text on
     * as a query or a prompt argument (a tool or an MCP prompt), where a "Name: " prefix would corrupt it
     * but an empty string would leave the tool nothing at all.
     */
    public static String orNoText(String text) {
        return StringUtils.isBlank(text) ? NO_TEXT : text;
    }

    public static String forModel(String senderName, String text) {
        return senderName + ": " + orNoText(text);
    }
}
