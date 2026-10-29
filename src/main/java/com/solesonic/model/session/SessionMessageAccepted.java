package com.solesonic.model.session;

import java.util.UUID;

/**
 * A message that started a run. Everything that follows, the sender's own reply included, arrives on
 * the session's stream; {@code runId} is how a client knows the run is the one it started.
 */
public record SessionMessageAccepted(UUID runId, UUID messageId) {
}
