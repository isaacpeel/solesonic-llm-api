package com.solesonic.service.a2a;

import com.solesonic.model.a2a.A2APrompt;
import com.solesonic.model.a2a.A2ATurnState;

/**
 * What a shared-session delegation reports beyond its text: the turn each reply says, and the
 * question the agent ended on. A shared-session run never parks on a question, because the
 * participant it is for may not answer for hours, so the question is handed here and the run ends.
 */
public interface A2ASessionTurnListener {

    void turn(A2ATurnState turnState);

    void prompt(String taskId, A2APrompt prompt);
}
