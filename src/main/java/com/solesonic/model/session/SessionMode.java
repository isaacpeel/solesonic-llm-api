package com.solesonic.model.session;

/**
 * What a shared session is for. {@code AGENT}: users playing with one A2A agent, which has the turn
 * and the prompt. {@code CONVERSATION}: users in one ordinary chat with the model, with no agent.
 */
public enum SessionMode {
    AGENT,
    CONVERSATION
}
