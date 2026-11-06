package com.solesonic.model.push;

/**
 * RFC 8030 §5.3. A push service may hold anything below {@link #HIGH} until an idle Android device
 * next wakes from Doze.
 */
public enum PushUrgency {
    VERY_LOW("very-low"),
    LOW("low"),
    NORMAL("normal"),
    HIGH("high");

    private final String headerValue;

    PushUrgency(String headerValue) {
        this.headerValue = headerValue;
    }

    public String headerValue() {
        return headerValue;
    }
}
