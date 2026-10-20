package com.solesonic.service.push;

import com.solesonic.model.push.PushEvent;
import com.solesonic.model.push.PushPayload;
import com.solesonic.model.push.PushPayloadType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Builds every push payload from fixed strings and ids alone. A push passes through Google's relay
 * and lands on a lock screen, so no chat title, message text or tool data is ever put in one.
 */
@Component
public class PushPayloadFactory {
    public static final int VERSION = 1;

    private static final String TURN_FINISHED_BODY = "Your reply is ready";
    private static final String TURN_FAILED_BODY = "Your request couldn't be completed";
    private static final String ELICITATION_WAITING_BODY = "A tool is waiting for your answer";
    private static final String TEST_BODY = "Notifications are working";
    private static final String TEST_URL = "/settings/notifications";
    private static final String TEST_TAG = "test";

    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String botName;

    public PushPayloadFactory(JsonMapper jsonMapper, Clock clock, @Value("${solesonic.llm.bot.name}") String botName) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.botName = botName;
    }

    public byte[] forEvent(PushEvent pushEvent) {
        PushPayload pushPayload = switch (pushEvent) {
            case PushEvent.TurnFinished turnFinished ->
                    payload(PushPayloadType.TURN_FINISHED, TURN_FINISHED_BODY, chatUrl(turnFinished.chatId()), chatTag(turnFinished.chatId()));
            case PushEvent.TurnFailed turnFailed ->
                    payload(PushPayloadType.TURN_FAILED, TURN_FAILED_BODY, chatUrl(turnFailed.chatId()), chatTag(turnFailed.chatId()));
            case PushEvent.ElicitationWaiting elicitationWaiting ->
                    payload(PushPayloadType.ELICITATION_WAITING, ELICITATION_WAITING_BODY, chatUrl(elicitationWaiting.chatId()),
                            "elicitation:" + elicitationWaiting.elicitationId());
        };

        return jsonMapper.writeValueAsBytes(pushPayload);
    }

    public byte[] test() {
        return jsonMapper.writeValueAsBytes(payload(PushPayloadType.TEST, TEST_BODY, TEST_URL, TEST_TAG));
    }

    private PushPayload payload(PushPayloadType type, String body, String url, String tag) {
        return new PushPayload(VERSION, type, botName, body, url, tag, DateTimeFormatter.ISO_INSTANT.format(clock.instant()));
    }

    private static String chatUrl(UUID chatId) {
        return "/chat/" + chatId;
    }

    private static String chatTag(UUID chatId) {
        return "chat:" + chatId;
    }
}
