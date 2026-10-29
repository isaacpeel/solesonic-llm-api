package com.solesonic.model.session;

/**
 * Exactly one of the two: a typed message, or an answer to the session's open question.
 */
public record SessionMessageRequest(String text, SessionPromptAnswer promptAnswer) {
}
