package com.solesonic.service.redis;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * What a route adds around a run's end. {@link #beforeTerminal} may publish frames of its own ahead
 * of the terminal one and can never fail the run: an error there is logged and the terminal frame
 * goes out regardless. {@link #afterTerminal} runs once the terminal frame is published, and
 * {@link #finished} once the run is over by any path, an interrupted one included.
 */
public interface TurnHooks {

    Mono<Void> beforeTerminal(UUID runId, TurnOutcome outcome);

    void afterTerminal(UUID runId, TurnOutcome outcome);

    void finished(UUID runId);
}
