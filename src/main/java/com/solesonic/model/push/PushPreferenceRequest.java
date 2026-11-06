package com.solesonic.model.push;

/**
 * Boxed so that a missing field is told apart from {@code false}. {@code turnComplete} and
 * {@code elicitation} are refused when missing; the interest and shared-session fields came later, so
 * a client that predates them leaves them out and they stay as they were.
 */
public record PushPreferenceRequest(Boolean turnComplete,
                                    Boolean elicitation,
                                    Boolean interestOffers,
                                    Boolean interestUpdates,
                                    Boolean sessionInvites,
                                    Boolean sessionTurns) {
}
