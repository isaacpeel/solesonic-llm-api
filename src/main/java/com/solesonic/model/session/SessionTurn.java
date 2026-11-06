package com.solesonic.model.session;

import java.util.List;
import java.util.UUID;

/**
 * Who a shared session waits on, as the agent last reported it, with the participants' names already
 * turned into their user ids. {@code next} is the agent's own word for what it expects next.
 * {@code inputHiddenFrom} names the participants the next typed message from those waited on is hidden
 * from; a turn saved before it existed reads as hiding nothing.
 */
public record SessionTurn(List<UUID> userIds, String next, boolean over, List<String> inputHiddenFrom) {

    public SessionTurn {
        inputHiddenFrom = inputHiddenFrom == null ? List.of() : List.copyOf(inputHiddenFrom);
    }
}
