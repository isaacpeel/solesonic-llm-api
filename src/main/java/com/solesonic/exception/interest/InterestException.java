package com.solesonic.exception.interest;

import com.solesonic.model.interest.InterestErrorCode;

/**
 * An interest request that cannot be served. The message reaches the client, so it is always one of
 * the fixed sentences below and never names a topic.
 */
public class InterestException extends RuntimeException {

    private final InterestErrorCode errorCode;

    public InterestException(InterestErrorCode errorCode, String userSafeMessage) {
        super(userSafeMessage);
        this.errorCode = errorCode;
    }

    public static InterestException offerNotFound() {
        return new InterestException(InterestErrorCode.OFFER_NOT_FOUND, "Offer not found.");
    }

    public static InterestException agentNotFound() {
        return new InterestException(InterestErrorCode.AGENT_NOT_FOUND, "Agent not found.");
    }

    public static InterestException updateNotFound() {
        return new InterestException(InterestErrorCode.UPDATE_NOT_FOUND, "Update not found.");
    }

    public static InterestException offerAlreadyDecided() {
        return new InterestException(InterestErrorCode.OFFER_ALREADY_DECIDED, "This offer has already been answered.");
    }

    public static InterestException offerExpired() {
        return new InterestException(InterestErrorCode.OFFER_EXPIRED, "This offer has expired.");
    }

    public static InterestException agentLimitReached() {
        return new InterestException(InterestErrorCode.AGENT_LIMIT_REACHED, "You already have the maximum number of agents.");
    }

    public static InterestException offerWithdrawn() {
        return new InterestException(InterestErrorCode.OFFER_WITHDRAWN, "This offer is no longer available.");
    }

    public static InterestException invalidAgentUpdate(String userSafeMessage) {
        return new InterestException(InterestErrorCode.INVALID_AGENT_UPDATE, userSafeMessage);
    }

    public static InterestException notPermitted() {
        return new InterestException(InterestErrorCode.NOT_PERMITTED, "You are not permitted to use news agents.");
    }

    public static InterestException runTooSoon() {
        return new InterestException(InterestErrorCode.RUN_TOO_SOON, "This agent ran a moment ago. Try again later.");
    }

    public InterestErrorCode getErrorCode() {
        return errorCode;
    }
}
