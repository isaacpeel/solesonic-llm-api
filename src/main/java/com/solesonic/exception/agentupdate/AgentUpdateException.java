package com.solesonic.exception.agentupdate;

import com.solesonic.model.agentupdate.AgentUpdateErrorCode;

/**
 * An agent update that cannot be delivered. The message goes back to the calling agent, so it never
 * repeats anything from the update itself.
 */
public class AgentUpdateException extends RuntimeException {

    private final AgentUpdateErrorCode errorCode;

    public AgentUpdateException(AgentUpdateErrorCode errorCode, String safeMessage) {
        super(safeMessage);
        this.errorCode = errorCode;
    }

    public static AgentUpdateException invalid(String safeMessage) {
        return new AgentUpdateException(AgentUpdateErrorCode.INVALID_UPDATE, safeMessage);
    }

    public static AgentUpdateException unknownAgent() {
        return new AgentUpdateException(AgentUpdateErrorCode.UNKNOWN_AGENT, "This agent may not deliver updates.");
    }

    public static AgentUpdateException userNotFound() {
        return new AgentUpdateException(AgentUpdateErrorCode.USER_NOT_FOUND, "User not found.");
    }

    public static AgentUpdateException rateLimited() {
        return new AgentUpdateException(AgentUpdateErrorCode.RATE_LIMITED, "Too many updates for this user today.");
    }

    public AgentUpdateErrorCode getErrorCode() {
        return errorCode;
    }
}
