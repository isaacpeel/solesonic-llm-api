package com.solesonic.service.redis;

/**
 * How a run ended. An interrupted run (a shutdown) has no outcome: it publishes nothing.
 */
public enum TurnOutcome {
    COMPLETED,
    FAILED,
    CANCELLED
}
