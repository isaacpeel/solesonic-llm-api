package com.solesonic.model.push;

public record PushPreferenceSummary(boolean turnComplete, boolean elicitation) {
    public static final PushPreferenceSummary DEFAULT = new PushPreferenceSummary(true, true);

    public static PushPreferenceSummary of(PushPreference pushPreference) {
        return new PushPreferenceSummary(pushPreference.isTurnComplete(), pushPreference.isElicitation());
    }
}
