package com.solesonic.model.memory;

/**
 * Which of a user's facts and preferences a plain chat turn carries. Standing instructions are
 * carried on every turn either way.
 */
public enum MemoryRetrievalMode {
    /**
     * Every active one, by confidence and recency, until the prompt budget runs out.
     */
    ALL,

    /**
     * Only those relevant to the message and the conversation so far.
     */
    RELEVANT
}
