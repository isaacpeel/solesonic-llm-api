package com.solesonic.model.chat.history;

/**
 * What a chat is for. A {@code SHARED} chat belongs to a session several users share with one agent
 * and is reached only through {@code /shared-sessions}; every other chat path asks for
 * {@code CONVERSATION} and finds nothing else.
 */
public enum ChatKind {
    CONVERSATION,
    SHARED
}
