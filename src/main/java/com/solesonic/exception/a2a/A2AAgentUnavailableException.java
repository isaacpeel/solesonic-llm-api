package com.solesonic.exception.a2a;

/**
 * A turn routed to an A2A agent the registry does not hold, which happens when a chat's sticky agent
 * outlives the card it was registered from. The message reaches the client verbatim, so it names only
 * the agent.
 */
public class A2AAgentUnavailableException extends RuntimeException {

    public A2AAgentUnavailableException(String agentName) {
        super("The %s agent is not available right now. Send your message again to continue without it."
                .formatted(agentName));
    }
}
