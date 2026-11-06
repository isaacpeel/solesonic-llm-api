package com.solesonic.model.session;

/**
 * {@code COMPLETED} still accepts messages: the agent reported the work done, and the participants
 * may start it again. {@code CLOSED} (a participant left) and {@code EXPIRED} (nobody wrote for the
 * idle window) accept none.
 */
public enum SessionStatus {
    ACTIVE,
    COMPLETED,
    CLOSED,
    EXPIRED;

    public boolean acceptsMessages() {
        return this == ACTIVE || this == COMPLETED;
    }
}
