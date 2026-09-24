package com.solesonic.model.chat;

/**
 * The closed set of failures a chat turn's terminal {@code RUN_ERROR} frame (and the {@code failure}
 * {@code CUSTOM} frame that precedes it) can carry as {@code code}.
 * <p>
 * {@link #TIMEOUT} and {@link #INTERNAL} keep the exact lowercase wire values this codebase shipped
 * before this enum existed, so no client already matching on {@code "timeout"}/{@code "internal"}
 * breaks. Every other constant is new and follows the UPPER_SNAKE convention the rest of this
 * codebase's error-code enums already use ({@code ImageGenerationErrorCode}, {@code GoogleErrorResponse}).
 */
public enum TurnErrorCode {

    /**
     * Generation did not finish inside the turn's deadline.
     */
    TIMEOUT("timeout"),

    /**
     * A model server, MCP server, or an integration it called failed in a way that may succeed on
     * a retry.
     */
    UPSTREAM_UNAVAILABLE("UPSTREAM_UNAVAILABLE"),

    /**
     * An upstream is throttling. Back off and retry.
     */
    RATE_LIMITED("RATE_LIMITED"),

    /**
     * An MCP or local tool call failed mid-turn — Jira, Xero, Google, image generation, or the RAG
     * pipeline.
     */
    TOOL_FAILURE("TOOL_FAILURE"),

    /**
     * An integration's grant is gone or was never given. Retrying cannot fix it; the user must
     * consent again.
     */
    RECONNECT_REQUIRED("RECONNECT_REQUIRED"),

    /**
     * The request itself was malformed or rejected before any generation was attempted.
     */
    VALIDATION("VALIDATION"),

    /**
     * The durable Redis stream a client's response is built from failed to read. The turn itself may
     * still be running; only the connection broke.
     */
    STREAM_UNAVAILABLE("STREAM_UNAVAILABLE"),

    /**
     * Anything else.
     */
    INTERNAL("internal");

    private final String wireValue;

    TurnErrorCode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
