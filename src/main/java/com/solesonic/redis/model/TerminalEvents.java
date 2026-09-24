package com.solesonic.redis.model;

import com.agui.community.core.event.EventType;

/**
 * The one definition of "this turn's stream will never be written to again", shared by everything
 * that has to know: the subscriber closing a response, cancel deciding there is nothing to stop, and
 * resume deciding a caught-up client gets a {@code 204} instead of a subscription that never ends.
 */
public final class TerminalEvents {

    private TerminalEvents() {
    }

    public static boolean isTerminal(String eventType) {
        return EventType.RUN_FINISHED.value().equalsIgnoreCase(eventType)
                || EventType.RUN_ERROR.value().equalsIgnoreCase(eventType);
    }
}
