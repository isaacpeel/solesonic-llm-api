package com.solesonic.model.push;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum PushPayloadType {
    TURN_FINISHED,
    TURN_FAILED,
    ELICITATION_WAITING,
    INTEREST_OFFER,
    INTEREST_UPDATE,
    SESSION_INVITE,
    SESSION_TURN,
    SESSION_STARTED,
    /**
     * Lower case on the wire: the service worker already matches this exact value.
     */
    @JsonProperty("shared_chat_message")
    SHARED_CHAT_MESSAGE,
    TEST
}
