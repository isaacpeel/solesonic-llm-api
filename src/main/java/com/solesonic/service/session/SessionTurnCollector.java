package com.solesonic.service.session;

import com.solesonic.model.a2a.A2APrompt;
import com.solesonic.model.a2a.A2ATurnState;
import com.solesonic.service.a2a.A2ASessionTurnListener;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What one shared-session run learned from the agent, read once the run ends: the last turn a reply
 * reported, and the question the run ended on. Written from the A2A client's thread and read from the
 * run's.
 */
public final class SessionTurnCollector implements A2ASessionTurnListener {

    public record CollectedPrompt(String taskId, A2APrompt prompt) {
    }

    private final AtomicReference<A2ATurnState> turn = new AtomicReference<>();
    private final AtomicReference<CollectedPrompt> prompt = new AtomicReference<>();

    @Override
    public void turn(A2ATurnState turnState) {
        turn.set(turnState);
    }

    @Override
    public void prompt(String taskId, A2APrompt prompt) {
        this.prompt.set(new CollectedPrompt(taskId, prompt));
    }

    public Optional<A2ATurnState> turn() {
        return Optional.ofNullable(turn.get());
    }

    public Optional<CollectedPrompt> prompt() {
        return Optional.ofNullable(prompt.get());
    }
}
