package com.solesonic.model.push;

public record PushPreferenceSummary(boolean turnComplete,
                                    boolean elicitation,
                                    boolean interestOffers,
                                    boolean interestUpdates,
                                    boolean sessionInvites,
                                    boolean sessionTurns) {
    public static final PushPreferenceSummary DEFAULT = new PushPreferenceSummary(true, true, true, true, true, true);

    public static PushPreferenceSummary of(PushPreference pushPreference) {
        return new PushPreferenceSummary(pushPreference.isTurnComplete(),
                pushPreference.isElicitation(),
                pushPreference.isInterestOffers(),
                pushPreference.isInterestUpdates(),
                pushPreference.isSessionInvites(),
                pushPreference.isSessionTurns());
    }
}
