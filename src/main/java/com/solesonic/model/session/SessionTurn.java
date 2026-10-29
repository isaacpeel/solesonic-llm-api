package com.solesonic.model.session;

import java.util.List;
import java.util.UUID;

/**
 * Who a shared session waits on, as the agent last reported it, with the participants' names already
 * turned into their user ids. {@code next} is the agent's own word for what it expects next.
 */
public record SessionTurn(List<UUID> userIds, String next, boolean over) {
}
