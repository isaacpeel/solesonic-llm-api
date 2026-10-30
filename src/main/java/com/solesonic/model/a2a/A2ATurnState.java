package com.solesonic.model.a2a;

import java.util.List;

/**
 * Who an agent's shared session waits on, as its reply said, read through
 * {@link A2AParticipantsExtension}. {@code participants} are the names the agent knows them by, never
 * user ids: the agent knows nothing else. {@code inputHiddenFrom} names who the next typed message from
 * the participants waited on is hidden from.
 */
public record A2ATurnState(List<String> participants, String next, boolean over, List<String> inputHiddenFrom) {
}
