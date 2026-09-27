package com.solesonic.redis.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalEventsTest {

    @Test
    void runFinishedEndsATurn() {
        assertThat(TerminalEvents.isTerminal("RUN_FINISHED")).isTrue();
    }

    /**
     * AG-UI ends an errored run with {@code RUN_ERROR} alone — no {@code RUN_FINISHED} follows it — so
     * treating only the latter as terminal would hold every failed turn's stream open.
     */
    @Test
    void runErrorEndsATurn() {
        assertThat(TerminalEvents.isTerminal("RUN_ERROR")).isTrue();
    }

    @Test
    void anythingElseDoesNot() {
        assertThat(TerminalEvents.isTerminal("TEXT_MESSAGE_CONTENT")).isFalse();
        assertThat(TerminalEvents.isTerminal("CUSTOM")).isFalse();
        assertThat(TerminalEvents.isTerminal("")).isFalse();
        assertThat(TerminalEvents.isTerminal(null)).isFalse();
    }
}
