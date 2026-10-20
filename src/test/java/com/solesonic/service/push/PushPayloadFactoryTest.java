package com.solesonic.service.push;

import com.solesonic.config.JacksonConfig;
import com.solesonic.model.push.PushEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole payload, asserted exactly: nothing from the conversation may ride along with a push,
 * and an extra field is how it would start.
 */
class PushPayloadFactoryTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID CHAT_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID RUN_ID = UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed");
    private static final UUID ELICITATION_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private final JsonMapper jsonMapper = new JacksonConfig().jsonMapper();
    private final PushPayloadFactory pushPayloadFactory = new PushPayloadFactory(jsonMapper, Clock.fixed(NOW, ZoneOffset.UTC), "Izzy");

    @Test
    void aFinishedTurnSaysOnlyThatTheReplyIsReady() {
        assertPayload(pushPayloadFactory.forEvent(new PushEvent.TurnFinished(CHAT_ID, RUN_ID)), """
                {"version":1,"type":"TURN_FINISHED","title":"Izzy","body":"Your reply is ready",
                 "url":"/chat/0f8fad5b-d9cb-469f-a165-70867728950e","tag":"chat:0f8fad5b-d9cb-469f-a165-70867728950e",
                 "timestamp":"2026-10-20T12:00:00Z"}
                """);
    }

    @Test
    void aFailedTurnSaysOnlyThatTheRequestFailed() {
        assertPayload(pushPayloadFactory.forEvent(new PushEvent.TurnFailed(CHAT_ID, RUN_ID)), """
                {"version":1,"type":"TURN_FAILED","title":"Izzy","body":"Your request couldn't be completed",
                 "url":"/chat/0f8fad5b-d9cb-469f-a165-70867728950e","tag":"chat:0f8fad5b-d9cb-469f-a165-70867728950e",
                 "timestamp":"2026-10-20T12:00:00Z"}
                """);
    }

    @Test
    void aWaitingElicitationIsTaggedByItsOwnId() {
        String expected = """
                {"version":1,"type":"ELICITATION_WAITING","title":"Izzy","body":"A tool is waiting for your answer",
                 "url":"/chat/0f8fad5b-d9cb-469f-a165-70867728950e","tag":"elicitation:7c9e6679-7425-40de-944b-e07fc1f90ae7",
                 "timestamp":"2026-10-20T12:00:00Z"}
                """;

        assertPayload(pushPayloadFactory.forEvent(new PushEvent.ElicitationWaiting(CHAT_ID, ELICITATION_ID, false)), expected);
        assertPayload(pushPayloadFactory.forEvent(new PushEvent.ElicitationWaiting(CHAT_ID, ELICITATION_ID, true)), expected);
    }

    @Test
    void aTestPushOpensTheNotificationSettings() {
        assertPayload(pushPayloadFactory.test(), """
                {"version":1,"type":"TEST","title":"Izzy","body":"Notifications are working",
                 "url":"/settings/notifications","tag":"test","timestamp":"2026-10-20T12:00:00Z"}
                """);
    }

    private void assertPayload(byte[] payload, String expected) {
        assertThat(jsonMapper.readTree(payload)).isEqualTo(jsonMapper.readTree(expected));
    }
}
