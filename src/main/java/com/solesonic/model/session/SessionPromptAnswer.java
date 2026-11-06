package com.solesonic.model.session;

import java.util.Map;
import java.util.UUID;

/**
 * An answer to the session's open question. {@code elicitationId} names the question it answers, so
 * an answer to one that has since been replaced is refused rather than read as the new one's.
 */
public record SessionPromptAnswer(UUID elicitationId, String action, Map<String, Object> content) {
}
